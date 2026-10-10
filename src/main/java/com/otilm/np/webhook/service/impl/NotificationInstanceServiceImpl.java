package com.otilm.np.webhook.service.impl;

import com.otilm.api.exception.AlreadyExistException;
import com.otilm.api.exception.NotFoundException;
import com.otilm.api.exception.ValidationError;
import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.common.attribute.common.content.data.CodeBlockAttributeContentData;
import com.otilm.api.model.common.attribute.v2.content.CodeBlockAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.StringAttributeContentV2;
import com.otilm.api.model.connector.notification.NotificationProviderInstanceDto;
import com.otilm.api.model.connector.notification.NotificationProviderInstanceRequestDto;
import com.otilm.api.model.connector.notification.NotificationProviderNotifyRequestDto;
import com.otilm.core.util.AttributeDefinitionUtils;
import com.otilm.np.webhook.attribute.Attributes;
import com.otilm.np.webhook.attribute.ContentType;
import com.otilm.np.webhook.dao.entity.NotificationInstance;
import com.otilm.np.webhook.dao.repository.NotificationInstanceRepository;
import com.otilm.np.webhook.exception.NotificationException;
import com.otilm.np.webhook.service.AttributeService;
import com.otilm.np.webhook.service.NotificationInstanceService;
import com.otilm.np.webhook.util.TemplateUtils;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

@Service
public class NotificationInstanceServiceImpl implements NotificationInstanceService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationInstanceServiceImpl.class);

    /** Delivery timestamp in milliseconds since the epoch, for replay detection by the receiver. */
    static final String HEADER_TIMESTAMP = "X-Webhook-Timestamp";

    /** Per-delivery nonce that lets the receiver discard duplicates. */
    static final String HEADER_NONCE = "X-Webhook-Nonce";

    private NotificationInstanceRepository notificationInstanceRepository;

    private AttributeService attributeService;

    /**
     * Whether DEBUG logging includes the notification request itself. The request carries values that must not reach
     * logs by default — the certificate-registration credential among them — so payload logging is a separate,
     * deliberate switch rather than a side effect of raising the log level.
     */
    private boolean logRequestPayload;

    @Autowired
    public void setNotificationInstanceRepository(NotificationInstanceRepository notificationInstanceRepository) {
        this.notificationInstanceRepository = notificationInstanceRepository;
    }

    @Value("${notification.log-request-payload:false}")
    public void setLogRequestPayload(boolean logRequestPayload) {
        this.logRequestPayload = logRequestPayload;
    }

    @Autowired
    public void setAttributeService(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @Override
    public List<NotificationProviderInstanceDto> listNotificationInstances() {
        List<NotificationInstance> instances;
        instances = notificationInstanceRepository.findAll();
        if (!instances.isEmpty()) {
            return instances.stream().map(NotificationInstance::mapToDto).toList();
        }
        return List.of();
    }

    @Override
    public NotificationProviderInstanceDto createNotificationInstance(NotificationProviderInstanceRequestDto request)
            throws AlreadyExistException {
        if (notificationInstanceRepository.findByName(request.getName()).isPresent()) {
            throw new AlreadyExistException(NotificationInstance.class, request.getName());
        }

        NotificationInstance notificationInstance = new NotificationInstance();
        notificationInstance.setUuid(UUID.randomUUID().toString());
        notificationInstance.setName(request.getName());
        applyRequestedConfiguration(notificationInstance, request);

        notificationInstanceRepository.save(notificationInstance);

        return notificationInstance.mapToDto();
    }

    @Override
    public NotificationProviderInstanceDto getNotificationInstance(UUID uuid) throws NotFoundException {
        return notificationInstanceRepository
                .findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(NotificationInstance.class, uuid))
                .mapToDto();
    }

    @Override
    public NotificationProviderInstanceDto updateNotificationInstance(UUID uuid,
            NotificationProviderInstanceRequestDto request) throws NotFoundException {
        NotificationInstance notificationInstance = notificationInstanceRepository
                .findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(NotificationInstance.class, uuid));

        applyRequestedConfiguration(notificationInstance, request);

        notificationInstanceRepository.save(notificationInstance);

        return notificationInstance.mapToDto();
    }

    @Override
    public void removeNotificationInstance(UUID uuid) throws NotFoundException {
        NotificationInstance instance = notificationInstanceRepository
                .findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(NotificationInstance.class, uuid));

        notificationInstanceRepository.delete(instance);
    }

    @Override
    public void sendNotification(UUID uuid, NotificationProviderNotifyRequestDto request) throws NotFoundException {
        logger
                .info("Received request to send webhook: eventType={}, resource={}", request.getEventType(),
                        request.getResource());
        NotificationInstance notificationInstance = notificationInstanceRepository
                .findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(NotificationInstance.class, uuid));

        if (logger.isDebugEnabled()) {
            logger
                    .debug("Request to send webhook received: {}",
                            logRequestPayload
                                    ? TemplateUtils.describeRequestForDebug(request)
                                    : TemplateUtils.summarizeRequest(request));
        }

        String url = notificationInstance.getUrl();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());

        Object content;
        ContentType contentType = notificationInstance.getContentType();
        if (contentType == ContentType.RAW_JSON) {
            content = request;
        } else {
            String contentTemplate = notificationInstance.getContentTemplate();
            content = TemplateUtils
                    .processFreeMarkerTemplate(TemplateUtils.CONTENT_TEMPLATE_LABEL, contentTemplate, request);
        }

        logger.info("Sending webhook to: {}, with timestamp {}, and nonce {}", url, timestamp, nonce);

        WebClient
                .builder()
                .baseUrl(url)
                .defaultHeader("Content-Type", notificationInstance.getContentType().getContentHeader())
                .defaultHeader(HEADER_TIMESTAMP, timestamp)
                .defaultHeader(HEADER_NONCE, nonce)
                .build()
                .post()
                .bodyValue(content)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), clientResponse -> {
                    logger.error("Failed to send webhook to {}: {}", url, clientResponse.statusCode());
                    return Mono
                            .error(new NotificationException(
                                    "Failed to send webhook to " + url + ": " + clientResponse.statusCode()));
                })
                .bodyToMono(Void.class)
                // Delivery is asynchronous, so the outcome is logged from the callbacks rather
                // than after subscribing. The explicit error consumer also keeps failures out of
                // Reactor's onErrorDropped logging, which would print the raw throwable and
                // bypass the sanitization in describeSendFailure.
                .doOnSuccess(unused -> logger.info("Webhook sent to: {}", url))
                .subscribe(unused -> {
                }, e -> logger.error("Error sending webhook to {}: {}", url, describeSendFailure(e)));
    }

    /**
     * Reads the webhook configuration out of the request attributes and applies it to the instance. Creation and update
     * configure an instance identically, so both go through here.
     */
    private void applyRequestedConfiguration(NotificationInstance notificationInstance,
            NotificationProviderInstanceRequestDto request) {
        final String url = AttributeDefinitionUtils
                .getSingleItemAttributeContentValue(Attributes.DATA_WEBHOOK_URL_NAME, request.getAttributes(),
                        StringAttributeContentV2.class)
                .getData();

        final ContentType contentType = ContentType
                .fromContentType(AttributeDefinitionUtils
                        .getSingleItemAttributeContentValue(Attributes.DATA_CONTENT_TYPE_NAME, request.getAttributes(),
                                StringAttributeContentV2.class)
                        .getData());

        String contentTemplate = null;
        if (contentType != ContentType.RAW_JSON) {
            contentTemplate = validatedContentTemplate(request, contentType);
        }

        notificationInstance.setUrl(url);
        notificationInstance.setContentType(contentType);
        notificationInstance.setContentTemplate(contentTemplate);
        notificationInstance
                .setAttributes(AttributeDefinitionUtils
                        .mergeAttributes(attributeService.getAllDataAttributes(request.getKind(), contentType),
                                request.getAttributes()));
    }

    /**
     * The content template the request carries, refused here when it will not parse. An operator editing a template is
     * told while they have it in front of them, rather than by a notification that failed to reach its receiver.
     */
    private static String validatedContentTemplate(NotificationProviderInstanceRequestDto request,
            ContentType contentType) {
        CodeBlockAttributeContentData data = AttributeDefinitionUtils
                .getSingleItemAttributeContentValue(Attributes.getDataContentTemplateName(contentType),
                        request.getAttributes(), CodeBlockAttributeContentV2.class)
                .getData();
        String contentTemplate = data == null ? null : data.getCode();
        if (contentTemplate == null) {
            throw new ValidationException(ValidationError.create("The content template is missing."));
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(contentTemplate), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(ValidationError.create("The content template could not be read."));
        }
        TemplateUtils.contentTemplateFailure(decoded).ifPresent(reason -> {
            throw new ValidationException(ValidationError.create(reason));
        });
        return contentTemplate;
    }

    /**
     * Transport failures carry safe, useful detail (host, port, HTTP status); anything else — for example a
     * request-body encoding failure — can embed request content in its message, so only the exception type is reported.
     * The request itself is inspectable through DEBUG logging.
     */
    static String describeSendFailure(Throwable e) {
        if (e instanceof NotificationException || e instanceof WebClientRequestException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

}
