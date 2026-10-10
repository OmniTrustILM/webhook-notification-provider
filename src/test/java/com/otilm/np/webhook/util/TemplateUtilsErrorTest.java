package com.otilm.np.webhook.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.connector.notification.NotificationProviderNotifyRequestDto;
import com.otilm.api.model.core.auth.Resource;
import com.otilm.api.model.core.other.ResourceEvent;
import com.otilm.np.webhook.exception.NotificationException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateUtilsErrorTest {

    private static final String TEMPLATE_LABEL = "webhook content";
    private static final String SENSITIVE_VALUE = "registration-credential-7f3a9";

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void captureLogs() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(TemplateUtils.class)).addAppender(logAppender);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(TemplateUtils.class)).detachAppender(logAppender);
        logAppender.stop();
    }

    private NotificationProviderNotifyRequestDto request() {
        NotificationProviderNotifyRequestDto request = new NotificationProviderNotifyRequestDto();
        request.setEvent(ResourceEvent.CERTIFICATE_STATUS_CHANGED);
        request.setResource(Resource.CERTIFICATE);
        request.setNotificationData(Map.of("credential", SENSITIVE_VALUE));
        return request;
    }

    /**
     * The content template is the operator's configuration, so a template that will not parse is answered as invalid
     * configuration rather than as a fault of the connector.
     */
    @Test
    void malformedTemplateIsAValidationFailure() {
        NotificationProviderNotifyRequestDto request = request();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> TemplateUtils.processFreeMarkerTemplate(TEMPLATE_LABEL, "${unclosed", request));
        assertEquals(1, ex.getErrors().size());
        assertTrue(ex.getMessage().contains("The " + TEMPLATE_LABEL + " template cannot be rendered"), ex.getMessage());
        assertTrue(ex.getMessage().contains("line"), "the parse failure must stay locatable in the template");
        assertNoPayloadExposure(ex);
    }

    @Test
    void unresolvedReferenceIsAValidationFailure() {
        NotificationProviderNotifyRequestDto request = request();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> TemplateUtils.processFreeMarkerTemplate(TEMPLATE_LABEL, "${totallyMissingVar}", request));
        assertTrue(ex.getMessage().contains(TEMPLATE_LABEL));
        assertTrue(ex.getMessage().contains("line"), "the rendering failure must stay locatable in the template");
        assertNoPayloadExposure(ex);
    }

    @Test
    void renderingFailureLogsTemplateAndEventIdentifiers() {
        NotificationProviderNotifyRequestDto request = request();
        assertThrows(ValidationException.class,
                () -> TemplateUtils.processFreeMarkerTemplate(TEMPLATE_LABEL, "${totallyMissingVar}", request));

        List<String> errorLogs = formattedLogs();
        assertFalse(errorLogs.isEmpty());
        assertTrue(
                errorLogs
                        .stream()
                        .anyMatch(message -> message.contains(TEMPLATE_LABEL)
                                && message.contains(String.valueOf(request.getEvent()))
                                && message.contains(String.valueOf(request.getResource()))),
                "failure log must identify the template, event, and resource: " + errorLogs);
    }

    /** Building the data model is the connector's own work, so its failure stays an internal one. */
    @Test
    void dataModelConversionFailureExposesNoPayload() {
        NotificationProviderNotifyRequestDto request = request();
        request.setNotificationData(new Object() {
            public String getCredential() {
                return SENSITIVE_VALUE;
            }

            public String getBoom() {
                // The cause message lands inside Jackson's conversion-failure message, so it must
                // not be forwarded either — only the exception type may be reported.
                throw new IllegalStateException("boom-" + SENSITIVE_VALUE);
            }
        });

        NotificationException ex = assertThrows(NotificationException.class,
                () -> TemplateUtils.processFreeMarkerTemplate(TEMPLATE_LABEL, "irrelevant", request));
        assertTrue(ex.getMessage().contains(TEMPLATE_LABEL));
        assertNoPayloadExposure(ex);
    }

    /**
     * FreeMarker quotes the offending value in coercion failures, so the raw message must never reach the log or the
     * exception returned to the platform.
     */
    @Test
    void coercionFailureExposesNoPayloadValue() {
        NotificationProviderNotifyRequestDto request = request();

        ValidationException ex = assertThrows(ValidationException.class, () -> TemplateUtils
                .processFreeMarkerTemplate(TEMPLATE_LABEL, "${notificationData.credential?number}", request));

        assertNoPayloadExposure(ex);
        assertTrue(ex.getMessage().contains("line"), "the failure must still point at the template position");
    }

    /** Date coercion quotes the value in its own message format, so it is covered separately. */
    @Test
    void dateCoercionFailureExposesNoPayloadValue() {
        NotificationProviderNotifyRequestDto request = request();

        ValidationException ex = assertThrows(ValidationException.class, () -> TemplateUtils
                .processFreeMarkerTemplate(TEMPLATE_LABEL, "${notificationData.credential?datetime}", request));

        assertNoPayloadExposure(ex);
    }

    /** Failures without a template position fall back to the exception type alone. */
    @Test
    void nonTemplateRenderFailureIsDescribedByTypeOnly() {
        assertEquals("IOException",
                TemplateUtils.renderFailureDiagnostics(new IOException("writer broke on " + SENSITIVE_VALUE)));
    }

    @Test
    void contentTemplateThatParsesHasNoFailure() {
        assertEquals(Optional.empty(), TemplateUtils.contentTemplateFailure("{\"event\": \"${event}\"}"));
    }

    /**
     * A reference the event may not carry is not a fault of the template on its own: whether it resolves depends on the
     * event, so only a template that will not parse is refused before a notification reaches it.
     */
    @Test
    void contentTemplateWithAnUnknownReferenceHasNoFailure() {
        assertEquals(Optional.empty(), TemplateUtils.contentTemplateFailure("${notificationData.missing}"));
    }

    /** Saving a template, the startup listing and a send that reaches the template report the same sentence. */
    @Test
    void contentTemplateFailureReadsAsTheSendFailure() {
        String malformed = "{\"text\": \"${unclosed\"}";
        NotificationProviderNotifyRequestDto request = request();
        ValidationException atSend = assertThrows(ValidationException.class, () -> TemplateUtils
                .processFreeMarkerTemplate(TemplateUtils.CONTENT_TEMPLATE_LABEL, malformed, request));

        Optional<String> failure = TemplateUtils.contentTemplateFailure(malformed);

        assertTrue(failure.isPresent());
        assertEquals(atSend.getErrors().getFirst().getErrorDescription(), failure.get());
    }

    /** The column names the character in the line, so a tab-indented template is not sent to the wrong place. */
    @Test
    void failurePositionCountsATabAsOneCharacter() {
        NotificationProviderNotifyRequestDto request = request();
        String template = "{\n\t\"text\": \"${notificationData.missing}\"\n}";
        int column = "\t\"text\": \"${".length() + 1;

        ValidationException ex = assertThrows(ValidationException.class,
                () -> TemplateUtils.processFreeMarkerTemplate(TEMPLATE_LABEL, template, request));

        assertTrue(ex.getMessage().contains("line 2, column " + column), ex.getMessage());
    }

    /** A content template renders the notification; it has no use for creating Java objects. */
    @Test
    void templateCannotCreateObjects() {
        NotificationProviderNotifyRequestDto request = request();
        String template = "<#assign made = \"freemarker.template.SimpleHash\"?new()>made";

        ValidationException ex = assertThrows(ValidationException.class,
                () -> TemplateUtils.processFreeMarkerTemplate(TEMPLATE_LABEL, template, request));

        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
    }

    @Test
    void summaryReportsIdentifiersWithoutPayload() {
        String summary = TemplateUtils.summarizeRequest(request());

        assertFalse(summary.contains(SENSITIVE_VALUE), "the summary must never carry payload values");
        assertTrue(summary.contains("notificationData=present"));
        assertTrue(summary.contains("recipients=0"));
    }

    @Test
    void summaryCountsResolvedRecipients() {
        NotificationProviderNotifyRequestDto request = request();
        com.otilm.api.model.connector.notification.NotificationRecipientDto recipient = new com.otilm.api.model.connector.notification.NotificationRecipientDto();
        recipient.setName("R1");
        request.setRecipients(java.util.List.of(recipient));

        assertTrue(TemplateUtils.summarizeRequest(request).contains("recipients=1"));
    }

    /** Recipient-less profiles send no recipients and events may carry no data; neither must fail. */
    @Test
    void summaryHandlesAbsentRecipientsAndData() {
        NotificationProviderNotifyRequestDto request = new NotificationProviderNotifyRequestDto();
        request.setEvent(ResourceEvent.CERTIFICATE_STATUS_CHANGED);
        request.setResource(Resource.CERTIFICATE);

        String summary = TemplateUtils.summarizeRequest(request);

        assertTrue(summary.contains("recipients=0"));
        assertTrue(summary.contains("notificationData=absent"));
    }

    @Test
    void debugDescriptionCarriesFullPayload() {
        assertTrue(TemplateUtils.describeRequestForDebug(request()).contains(SENSITIVE_VALUE),
                "DEBUG description is the sanctioned payload-visibility mechanism and must serialize the payload");
    }

    @Test
    void debugDescriptionOfUnserializableRequestIsPayloadFree() {
        NotificationProviderNotifyRequestDto request = request();
        request.setNotificationData(new Object() {
            public String getCredential() {
                return SENSITIVE_VALUE;
            }

            public String getBoom() {
                throw new IllegalStateException("boom");
            }
        });

        String description = TemplateUtils.describeRequestForDebug(request);
        assertTrue(description.contains("unserializable"));
        assertFalse(description.contains(SENSITIVE_VALUE));
    }

    private void assertNoPayloadExposure(Exception ex) {
        assertFalse(ex.getMessage().contains(SENSITIVE_VALUE),
                "exception message must not carry the request payload: " + ex.getMessage());
        for (String message : formattedLogs()) {
            assertFalse(message.contains(SENSITIVE_VALUE), "log output must not carry the request payload: " + message);
        }
    }

    private List<String> formattedLogs() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

}
