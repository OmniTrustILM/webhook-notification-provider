package com.otilm.np.webhook.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.otilm.api.exception.ValidationError;
import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.connector.notification.NotificationProviderNotifyRequestDto;
import com.otilm.np.webhook.exception.NotificationException;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TemplateUtils {

    private static final Logger logger = LoggerFactory.getLogger(TemplateUtils.class);

    /**
     * Shared mapper: constructing one per call is expensive, and the registered modules keep types such as
     * {@code java.time} serializable instead of degrading DEBUG output to the unserializable placeholder.
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

    /** Names the content template in every message about it, so saving, startup and send read alike. */
    public static final String CONTENT_TEMPLATE_LABEL = "webhook content";

    private TemplateUtils() {
    }

    /**
     * Payload-free summary of a notification request: identifiers and counts only. This is what DEBUG logging reports
     * unless payload logging is explicitly switched on.
     */
    public static String summarizeRequest(NotificationProviderNotifyRequestDto request) {
        return "event=%s, resource=%s, recipients=%d, notificationData=%s"
                .formatted(request.getEvent(), request.getResource(),
                        request.getRecipients() == null ? 0 : request.getRecipients().size(),
                        request.getNotificationData() == null ? "absent" : "present");
    }

    /**
     * Serializes the whole notification request for opt-in DEBUG logging — the sanctioned way to inspect payload
     * content when debugging. Uses explicit JSON serialization because the request's {@code toString} may exclude the
     * payload-bearing fields, which would make DEBUG output silently incomplete. A request that cannot be serialized —
     * including one whose own accessors fail — yields a payload-free placeholder rather than disrupting the send flow.
     */
    public static String describeRequestForDebug(NotificationProviderNotifyRequestDto request) {
        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException | RuntimeException e) {
            return "unserializable notification request (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Renders the given FreeMarker template against the notification request.
     *
     * <p>
     * Failure logs and exception messages carry the template label, the event and resource identifiers, and the
     * underlying error only — never the request payload or the data model. The request's {@code notificationData} and
     * {@code objectData} can hold sensitive values (for example a certificate-registration credential), and the thrown
     * exception's message becomes this connector's HTTP error response toward the platform, so payload content must not
     * reach either. Full request visibility for debugging remains available through the DEBUG-level logging of the send
     * flow.
     * </p>
     *
     * <p>
     * A template that will not parse or render is the operator's configuration rather than a fault of this connector,
     * so it is refused as a validation failure; only a failure to build the data model is the connector's own.
     * </p>
     *
     * @param templateLabel identifies the rendered template in errors, e.g. "webhook content"
     */
    public static String processFreeMarkerTemplate(String templateLabel, String templateSource,
            NotificationProviderNotifyRequestDto request) {
        // Convert a request to a Map instead of using the JSON node directly
        Map<String, Object> dataModel;
        try {
            dataModel = OBJECT_MAPPER.convertValue(request, new TypeReference<>() {
            });
        } catch (IllegalArgumentException e) {
            // Only the exception type is reported: Jackson conversion messages can embed model
            // paths or content, and this internal failure has no template-author diagnostics value.
            logger
                    .error("Failed to build the {} template data model: event={}, resource={}, error={}", templateLabel,
                            request.getEvent(), request.getResource(), e.getClass().getSimpleName());
            throw new NotificationException("Failed to build the " + templateLabel + " template data model ("
                    + e.getClass().getSimpleName() + ")");
        }

        Template template;
        try {
            template = parse(templateLabel, templateSource);
        } catch (IOException e) {
            // Parsing happens before the data model is bound, so this message describes the
            // operator's own template only and cannot quote payload values.
            logger
                    .error("Failed to parse the {} template: event={}, resource={}, error={}", templateLabel,
                            request.getEvent(), request.getResource(), e.getMessage());
            throw new ValidationException(ValidationError.create(parseFailureDescription(templateLabel, e)));
        }

        // Process the template with the data model
        StringWriter stringWriter = new StringWriter();
        try {
            template.process(dataModel, stringWriter);
        } catch (TemplateException | IOException e) {
            String diagnostics = renderFailureDiagnostics(e);
            logger
                    .error("Failed to render the {} template: event={}, resource={}, error={}", templateLabel,
                            request.getEvent(), request.getResource(), diagnostics);
            throw new ValidationException(
                    ValidationError.create("The " + templateLabel + " template cannot be rendered: " + diagnostics));
        }

        return stringWriter.toString();
    }

    /**
     * Why this content template will not render, for a caller checking one before any notification reaches it: the
     * message the operator would be given at send, or empty when it parses. Only parsing can be checked here, since
     * whether a reference resolves depends on the event that is rendered.
     */
    public static Optional<String> contentTemplateFailure(String templateSource) {
        try {
            parse(CONTENT_TEMPLATE_LABEL, templateSource);
            return Optional.empty();
        } catch (IOException e) {
            return Optional.of(parseFailureDescription(CONTENT_TEMPLATE_LABEL, e));
        }
    }

    private static Template parse(String templateLabel, String templateSource) throws IOException {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_33);
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        // A template renders the notification and has no use for creating Java objects
        cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
        // A reported column counts characters, so a tab-indented template points at the right place
        cfg.setTabSize(1);
        return new Template(templateLabel, new StringReader(templateSource), cfg);
    }

    private static String parseFailureDescription(String templateLabel, IOException failure) {
        return "The %s template cannot be rendered: %s".formatted(templateLabel, failure.getMessage());
    }

    /**
     * Payload-free description of a rendering failure. FreeMarker quotes the value that failed to evaluate in its
     * message — {@code ${credential?number}} embeds the credential verbatim — so only the exception type and the
     * position in the template are reported. The template is the operator's own content, so the position identifies the
     * failing expression for them.
     */
    static String renderFailureDiagnostics(Exception e) {
        if (e instanceof TemplateException templateException) {
            return "%s at line %s, column %s"
                    .formatted(e.getClass().getSimpleName(), templateException.getLineNumber(),
                            templateException.getColumnNumber());
        }
        return e.getClass().getSimpleName();
    }

}
