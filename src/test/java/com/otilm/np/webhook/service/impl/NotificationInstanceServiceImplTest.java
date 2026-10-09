package com.otilm.np.webhook.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.otilm.api.exception.AlreadyExistException;
import com.otilm.api.exception.NotFoundException;
import com.otilm.api.model.client.attribute.RequestAttribute;
import com.otilm.api.model.client.attribute.RequestAttributeV2;
import com.otilm.api.model.common.attribute.common.content.AttributeContentType;
import com.otilm.api.model.common.attribute.common.content.data.CodeBlockAttributeContentData;
import com.otilm.api.model.common.attribute.v2.content.BaseAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.CodeBlockAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.StringAttributeContentV2;
import com.otilm.api.model.connector.notification.NotificationProviderInstanceDto;
import com.otilm.api.model.connector.notification.NotificationProviderInstanceRequestDto;
import com.otilm.api.model.connector.notification.NotificationProviderNotifyRequestDto;
import com.otilm.api.model.core.auth.Resource;
import com.otilm.api.model.core.other.ResourceEvent;
import com.otilm.np.webhook.attribute.Attributes;
import com.otilm.np.webhook.attribute.ContentType;
import com.otilm.np.webhook.dao.entity.NotificationInstance;
import com.otilm.np.webhook.dao.repository.NotificationInstanceRepository;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationInstanceServiceImplTest {

    private static final String SENSITIVE_VALUE = "debug-visible-credential";
    private static final String INSTANCE_NAME = "configured-webhook";
    private static final String WEBHOOK_URL = "https://example.com/webhook";
    private static final String TEMPLATE_SOURCE = "{\"event\": \"${event}\"}";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String HEADER_TIMESTAMP = "X-Webhook-Timestamp";
    private static final String HEADER_NONCE = "X-Webhook-Nonce";

    @Mock
    private NotificationInstanceRepository repository;

    private NotificationInstanceServiceImpl service;

    private AwaitableLogAppender logAppender;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        service = new NotificationInstanceServiceImpl();
        service.setNotificationInstanceRepository(repository);
        service.setAttributeService(new AttributeServiceImpl());

        Logger serviceLogger = serviceLogger();
        originalLevel = serviceLogger.getLevel();
        logAppender = new AwaitableLogAppender();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger serviceLogger = serviceLogger();
        serviceLogger.setLevel(originalLevel);
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private Logger serviceLogger() {
        return (Logger) LoggerFactory.getLogger(NotificationInstanceServiceImpl.class);
    }

    private NotificationProviderNotifyRequestDto request() {
        NotificationProviderNotifyRequestDto request = new NotificationProviderNotifyRequestDto();
        request.setEvent(ResourceEvent.CERTIFICATE_STATUS_CHANGED);
        request.setResource(Resource.CERTIFICATE);
        request.setNotificationData(Map.of("credential", SENSITIVE_VALUE));
        return request;
    }

    private UUID persistedInstance(ContentType contentType, String contentTemplate) {
        return persistedInstance(contentType, contentTemplate, "http://localhost:9");
    }

    private UUID persistedInstance(ContentType contentType, String contentTemplate, String url) {
        UUID uuid = UUID.randomUUID();
        NotificationInstance instance = new NotificationInstance();
        instance.setUuid(uuid);
        instance.setName("test-webhook");
        instance.setUrl(url);
        instance.setContentType(contentType);
        instance.setContentTemplate(contentTemplate);
        when(repository.findByUuid(uuid)).thenReturn(Optional.of(instance));
        return uuid;
    }

    /**
     * The delivery outcome is logged from the asynchronous callbacks, so a webhook that the receiver accepts must still
     * report the send — covered against a real endpoint because the success callback is never reached when the endpoint
     * is unreachable.
     */
    @Test
    void sendNotification_acceptedByReceiver_logsTheCompletedSend() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        try {
            UUID uuid = persistedInstance(ContentType.RAW_JSON, null,
                    "http://localhost:" + receiver.getAddress().getPort());

            assertDoesNotThrow(() -> service.sendNotification(uuid, request()));

            assertTrue(awaitLog(message -> message.startsWith("Webhook sent to")),
                    "an accepted webhook must be reported as sent: " + formattedLogs());
        } finally {
            receiver.stop(0);
        }
    }

    /**
     * The delivery headers and the request shape are this connector's contract with the receiver, so they are asserted
     * against a real endpoint rather than assumed. The header names are spelled out literally: reading them through the
     * production constants would keep passing if a constant's value changed.
     */
    @Test
    void deliveryHeaderNamesAreTheDocumentedOnes() {
        assertEquals(HEADER_TIMESTAMP, NotificationInstanceServiceImpl.HEADER_TIMESTAMP);
        assertEquals(HEADER_NONCE, NotificationInstanceServiceImpl.HEADER_NONCE);
    }

    @Test
    void sendNotification_deliversThePayloadWithTheDocumentedHeaders() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<Headers> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            headers.set(exchange.getRequestHeaders());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        try {
            UUID uuid = persistedInstance(ContentType.JSON,
                    Base64.getEncoder().encodeToString(TEMPLATE_SOURCE.getBytes(StandardCharsets.UTF_8)),
                    "http://localhost:" + receiver.getAddress().getPort());

            service.sendNotification(uuid, request());

            assertTrue(awaitLog(message -> message.startsWith("Webhook sent to")),
                    "the send was never reported as completed: " + formattedLogs());

            assertEquals("POST", method.get());
            assertEquals(ContentType.JSON.getContentHeader(), headers.get().getFirst("Content-Type"));

            String timestamp = headers.get().getFirst(HEADER_TIMESTAMP);
            assertNotNull(timestamp, "the delivery timestamp header must be present");
            assertTrue(Long.parseLong(timestamp) > 0, "the timestamp must be epoch milliseconds");

            String nonce = headers.get().getFirst(HEADER_NONCE);
            assertNotNull(nonce, "the delivery nonce header must be present");
            assertFalse(nonce.isBlank());

            // The template renders against the JSON view of the request, where the event appears
            // as its platform code rather than as the enum name.
            assertEquals("{\"event\": \"%s\"}".formatted(ResourceEvent.CERTIFICATE_STATUS_CHANGED.getCode()),
                    body.get());
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void sendNotification_rawJsonDeliversTheRequestItself() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<Headers> headers = new AtomicReference<>();

        HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext("/", exchange -> {
            headers.set(exchange.getRequestHeaders());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        try {
            UUID uuid = persistedInstance(ContentType.RAW_JSON, null,
                    "http://localhost:" + receiver.getAddress().getPort());

            service.sendNotification(uuid, request());

            assertTrue(awaitLog(message -> message.startsWith("Webhook sent to")),
                    "the send was never reported as completed: " + formattedLogs());

            assertEquals(ContentType.RAW_JSON.getContentHeader(), headers.get().getFirst("Content-Type"));
            assertNotNull(headers.get().getFirst(HEADER_TIMESTAMP));
            assertNotNull(headers.get().getFirst(HEADER_NONCE));
            assertTrue(body.get().contains(SENSITIVE_VALUE), "raw delivery forwards the notification request as-is");
        } finally {
            receiver.stop(0);
        }
    }

    /**
     * Payload logging is opt-in: raising the log level alone must never write the request into the logs, only the
     * payload-free summary.
     */
    @Test
    void sendNotification_debugLoggingWithoutOptIn_logsNoPayload() {
        UUID uuid = persistedInstance(ContentType.RAW_JSON, null);
        serviceLogger().setLevel(Level.DEBUG);

        assertDoesNotThrow(() -> service.sendNotification(uuid, request()));

        assertTrue(formattedLogs().stream().noneMatch(message -> message.contains(SENSITIVE_VALUE)),
                "DEBUG alone must not write the request payload: " + formattedLogs());
        assertTrue(formattedLogs().stream().anyMatch(message -> message.contains("notificationData=present")),
                "the payload-free summary must still be logged: " + formattedLogs());
    }

    /** With payload logging switched on, the full request is available for troubleshooting. */
    @Test
    void sendNotification_debugLoggingWithOptIn_retainsRequestVisibility() {
        UUID uuid = persistedInstance(ContentType.RAW_JSON, null);
        service.setLogRequestPayload(true);
        serviceLogger().setLevel(Level.DEBUG);

        assertDoesNotThrow(() -> service.sendNotification(uuid, request()));

        assertTrue(formattedLogs().stream().anyMatch(message -> message.contains(SENSITIVE_VALUE)),
                "with payload logging enabled the request content must be available");
    }

    @Test
    void describeSendFailure_transportErrorsKeepTheirMessage() {
        assertTrue(NotificationInstanceServiceImpl
                .describeSendFailure(
                        new com.otilm.np.webhook.exception.NotificationException("Failed to send webhook to x: 500"))
                .contains("Failed to send webhook to x: 500"));
    }

    @Test
    void describeSendFailure_connectionErrorsKeepTheirMessage() {
        org.springframework.web.reactive.function.client.WebClientRequestException transport = new org.springframework.web.reactive.function.client.WebClientRequestException(
                new java.net.ConnectException("Connection refused: localhost:9"),
                org.springframework.http.HttpMethod.POST, java.net.URI.create("http://localhost:9"),
                new org.springframework.http.HttpHeaders());
        assertTrue(NotificationInstanceServiceImpl.describeSendFailure(transport).contains("Connection refused"));
    }

    @Test
    void describeSendFailure_otherErrorsAreReducedToTheirType() {
        String described = NotificationInstanceServiceImpl
                .describeSendFailure(new IllegalStateException("encoder failure exposing " + SENSITIVE_VALUE));
        assertEquals("IllegalStateException", described, "unknown failures must be reduced to the exception type");
    }

    @Test
    void sendNotification_templatedContent_rendersWithoutPayloadInDefaultLogs() throws InterruptedException {
        UUID uuid = persistedInstance(ContentType.JSON,
                Base64.getEncoder().encodeToString("{\"event\": \"${event}\"}".getBytes(StandardCharsets.UTF_8)));

        assertDoesNotThrow(() -> service.sendNotification(uuid, request()));
        // Delivery is asynchronous: wait for its outcome to be logged, otherwise the assertion
        // could run before the send path had a chance to log anything at all.
        assertTrue(awaitLog(
                message -> message.startsWith("Error sending webhook to") || message.startsWith("Webhook sent to")),
                "the asynchronous send outcome was never logged");

        assertTrue(formattedLogs().stream().noneMatch(message -> message.contains(SENSITIVE_VALUE)),
                "default-level logs must not carry the request payload");
    }

    // ---- instance lifecycle ----

    @Test
    void createNotificationInstance_persistsTheConfiguredWebhook() throws AlreadyExistException {
        when(repository.findByName(INSTANCE_NAME)).thenReturn(Optional.empty());

        NotificationProviderInstanceDto dto = service.createNotificationInstance(instanceRequest());

        NotificationInstance saved = savedInstance();
        assertEquals(INSTANCE_NAME, saved.getName());
        assertEquals(WEBHOOK_URL, saved.getUrl());
        assertEquals(ContentType.JSON, saved.getContentType());
        assertEquals(TEMPLATE_SOURCE, saved.getContentTemplate());
        assertNotNull(saved.getUuid());

        assertEquals(INSTANCE_NAME, dto.getName());
        assertEquals(saved.getUuid().toString(), dto.getUuid());
        assertEquals(3, dto.getAttributes().size());
    }

    /**
     * RAW_JSON forwards the notification request unchanged, so no content template is configured and the template
     * attribute is absent from the request.
     */
    @Test
    void createNotificationInstance_rawJsonNeedsNoContentTemplate() throws AlreadyExistException {
        when(repository.findByName(INSTANCE_NAME)).thenReturn(Optional.empty());

        NotificationProviderInstanceRequestDto request = instanceRequest();
        request
                .setAttributes(List
                        .of(stringAttribute(Attributes.DATA_WEBHOOK_URL_UUID, Attributes.DATA_WEBHOOK_URL_NAME,
                                WEBHOOK_URL),
                                stringAttribute(Attributes.DATA_CONTENT_TYPE_UUID, Attributes.DATA_CONTENT_TYPE_NAME,
                                        ContentType.RAW_JSON.name())));

        NotificationProviderInstanceDto dto = service.createNotificationInstance(request);

        NotificationInstance saved = savedInstance();
        assertEquals(ContentType.RAW_JSON, saved.getContentType());
        assertEquals(WEBHOOK_URL, saved.getUrl());
        assertEquals(2, dto.getAttributes().size());
    }

    /** Instance names identify an instance to the operator, so they have to stay unique. */
    @Test
    void createNotificationInstance_rejectsADuplicateName() {
        NotificationInstance existing = new NotificationInstance();
        existing.setUuid(UUID.randomUUID());
        existing.setName(INSTANCE_NAME);
        when(repository.findByName(INSTANCE_NAME)).thenReturn(Optional.of(existing));

        NotificationProviderInstanceRequestDto request = instanceRequest();
        assertThrows(AlreadyExistException.class, () -> service.createNotificationInstance(request));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getNotificationInstance_returnsThePersistedInstance() throws NotFoundException {
        UUID uuid = persistedInstance(ContentType.RAW_JSON, null);

        NotificationProviderInstanceDto dto = service.getNotificationInstance(uuid);

        assertEquals(uuid.toString(), dto.getUuid());
        assertEquals("test-webhook", dto.getName());
    }

    @Test
    void getNotificationInstance_rejectsAnUnknownUuid() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByUuid(unknown)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.getNotificationInstance(unknown));
    }

    @Test
    void updateNotificationInstance_replacesTheConfiguration() throws NotFoundException {
        UUID uuid = persistedInstance(ContentType.RAW_JSON, null, "http://localhost:9");

        service.updateNotificationInstance(uuid, instanceRequest());

        NotificationInstance saved = savedInstance();
        assertEquals(uuid, saved.getUuid());
        assertEquals(WEBHOOK_URL, saved.getUrl());
        assertEquals(ContentType.JSON, saved.getContentType());
        assertEquals(TEMPLATE_SOURCE, saved.getContentTemplate());
    }

    @Test
    void updateNotificationInstance_rejectsAnUnknownUuid() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByUuid(unknown)).thenReturn(Optional.empty());
        NotificationProviderInstanceRequestDto request = instanceRequest();

        assertThrows(NotFoundException.class, () -> service.updateNotificationInstance(unknown, request));
    }

    @Test
    void removeNotificationInstance_deletesThePersistedInstance() throws NotFoundException {
        UUID uuid = persistedInstance(ContentType.RAW_JSON, null);

        service.removeNotificationInstance(uuid);

        ArgumentCaptor<NotificationInstance> captor = ArgumentCaptor.forClass(NotificationInstance.class);
        verify(repository).delete(captor.capture());
        assertEquals(uuid, captor.getValue().getUuid());
    }

    @Test
    void removeNotificationInstance_rejectsAnUnknownUuid() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByUuid(unknown)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.removeNotificationInstance(unknown));
    }

    @Test
    void listNotificationInstances_mapsEveryPersistedInstance() {
        NotificationInstance first = new NotificationInstance();
        first.setUuid(UUID.randomUUID());
        first.setName("first");
        NotificationInstance second = new NotificationInstance();
        second.setUuid(UUID.randomUUID());
        second.setName("second");
        when(repository.findAll()).thenReturn(List.of(first, second));

        List<NotificationProviderInstanceDto> instances = service.listNotificationInstances();

        assertEquals(List.of("first", "second"),
                instances.stream().map(NotificationProviderInstanceDto::getName).toList());
    }

    @Test
    void listNotificationInstances_returnsNothingWhenNoneAreConfigured() {
        when(repository.findAll()).thenReturn(List.of());

        assertTrue(service.listNotificationInstances().isEmpty());
    }

    private NotificationInstance savedInstance() {
        ArgumentCaptor<NotificationInstance> captor = ArgumentCaptor.forClass(NotificationInstance.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private NotificationProviderInstanceRequestDto instanceRequest() {
        CodeBlockAttributeContentV2 template = new CodeBlockAttributeContentV2();
        template
                .setData(new CodeBlockAttributeContentData(ContentType.JSON.getLanguage(),
                        Base64.getEncoder().encodeToString(TEMPLATE_SOURCE.getBytes(StandardCharsets.UTF_8))));

        RequestAttributeV2 contentTemplate = new RequestAttributeV2();
        contentTemplate.setUuid(UUID.fromString(Attributes.getDataContentTemplateUuid(ContentType.JSON)));
        contentTemplate.setName(Attributes.getDataContentTemplateName(ContentType.JSON));
        contentTemplate.setContentType(AttributeContentType.CODEBLOCK);
        contentTemplate.setContent(List.<BaseAttributeContentV2<?>>of(template));

        List<RequestAttribute> attributes = List
                .of(stringAttribute(Attributes.DATA_WEBHOOK_URL_UUID, Attributes.DATA_WEBHOOK_URL_NAME, WEBHOOK_URL),
                        stringAttribute(Attributes.DATA_CONTENT_TYPE_UUID, Attributes.DATA_CONTENT_TYPE_NAME,
                                ContentType.JSON.name()),
                        contentTemplate);

        NotificationProviderInstanceRequestDto request = new NotificationProviderInstanceRequestDto();
        request.setName(INSTANCE_NAME);
        request.setKind("WEBHOOK");
        request.setAttributes(attributes);
        return request;
    }

    private RequestAttributeV2 stringAttribute(String uuid, String name, String value) {
        RequestAttributeV2 attribute = new RequestAttributeV2();
        attribute.setUuid(UUID.fromString(uuid));
        attribute.setName(name);
        attribute.setContentType(AttributeContentType.STRING);
        attribute.setContent(List.<BaseAttributeContentV2<?>>of(new StringAttributeContentV2(value)));
        return attribute;
    }

    private boolean awaitLog(Predicate<String> matcher) throws InterruptedException {
        return logAppender.await(matcher, AWAIT_TIMEOUT);
    }

    private List<String> formattedLogs() {
        return logAppender.messages();
    }

    /**
     * Captures log output and can block until a message arrives. Webhook delivery is asynchronous, so the send outcome
     * has to be awaited rather than read straight after the call returns.
     */
    private static final class AwaitableLogAppender extends AppenderBase<ILoggingEvent> {

        private final List<String> messages = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            synchronized (messages) {
                messages.add(event.getFormattedMessage());
                messages.notifyAll();
            }
        }

        private List<String> messages() {
            synchronized (messages) {
                return List.copyOf(messages);
            }
        }

        private boolean await(Predicate<String> matcher, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (messages) {
                while (messages.stream().noneMatch(matcher)) {
                    long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remainingMillis <= 0) {
                        return false;
                    }
                    messages.wait(remainingMillis);
                }
                return true;
            }
        }
    }

}
