package com.otilm.np.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.otilm.np.webhook.attribute.ContentType;
import com.otilm.np.webhook.dao.entity.NotificationInstance;
import com.otilm.np.webhook.dao.repository.NotificationInstanceRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentTemplatePreflightTest {

    @Mock
    private NotificationInstanceRepository repository;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void captureLogs() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(ContentTemplatePreflight.class)).addAppender(logAppender);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(ContentTemplatePreflight.class)).detachAppender(logAppender);
        logAppender.stop();
    }

    private static NotificationInstance instance(String name, ContentType contentType, String contentTemplate) {
        NotificationInstance instance = new NotificationInstance();
        instance.setName(name);
        instance.setContentType(contentType);
        instance
                .setContentTemplate(contentTemplate == null
                        ? null
                        : Base64.getEncoder().encodeToString(contentTemplate.getBytes(StandardCharsets.UTF_8)));
        return instance;
    }

    /**
     * A raw JSON instance has no template to check, and a reference the event may not carry depends on the event, so
     * only a template that will not parse is named.
     */
    @Test
    void namesOnlyTheInstancesWhoseTemplateWillNotParse() {
        when(repository.findAll())
                .thenReturn(List
                        .of(instance("plain", ContentType.JSON, "{\"event\": \"${event}\"}"),
                                instance("raw", ContentType.RAW_JSON, null),
                                instance("unknown reference", ContentType.XML, "<a>${notificationData.missing}</a>"),
                                instance("malformed json", ContentType.JSON, "{\"text\": \"${unclosed\"}"),
                                instance("malformed xml", ContentType.XML, "<#if></a>")));

        List<String> reported = new ContentTemplatePreflight(repository).unrenderableTemplates();

        assertEquals(2, reported.size(), reported.toString());
        assertTrue(reported.get(0).startsWith("'malformed json': The webhook content template cannot be rendered"),
                reported.toString());
        assertTrue(reported.get(1).startsWith("'malformed xml': The webhook content template cannot be rendered"),
                reported.toString());
    }

    @Test
    void anInstanceWhoseTemplateCannotBeReadIsNamedRatherThanThrown() {
        NotificationInstance unreadable = new NotificationInstance();
        unreadable.setName("not base64");
        unreadable.setContentType(ContentType.JSON);
        unreadable.setContentTemplate("{\"not\": \"base64\"}");
        when(repository.findAll()).thenReturn(List.of(unreadable));

        List<String> reported = new ContentTemplatePreflight(repository).unrenderableTemplates();

        assertEquals(List.of("'not base64': IllegalArgumentException"), reported);
    }

    @Test
    void reportsEachInstanceWhoseTemplateWillNotParseAtWarn() {
        when(repository.findAll())
                .thenReturn(List
                        .of(instance("plain", ContentType.JSON, "{\"event\": \"${event}\"}"),
                                instance("malformed", ContentType.JSON, "{\"text\": \"${unclosed\"}")));

        new ContentTemplatePreflight(repository).reportUnrenderableTemplates();

        assertEquals(1, logAppender.list.size(), logAppender.list.toString());
        ILoggingEvent event = logAppender.list.getFirst();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event
                .getFormattedMessage()
                .startsWith("Notification instance 'malformed': The webhook content template cannot be rendered"),
                event.getFormattedMessage());
    }

    /** The check only reports; a store it cannot read must not keep the connector from starting. */
    @Test
    void anUnreadableStoreIsReportedWithoutStoppingStartup() {
        when(repository.findAll()).thenThrow(new DataAccessResourceFailureException("connection reset"));
        ContentTemplatePreflight preflight = new ContentTemplatePreflight(repository);

        assertDoesNotThrow(preflight::reportUnrenderableTemplates);

        assertEquals(1, logAppender.list.size(), logAppender.list.toString());
        assertEquals(Level.WARN, logAppender.list.getFirst().getLevel());
        assertEquals("Stored content templates could not be checked: DataAccessResourceFailureException",
                logAppender.list.getFirst().getFormattedMessage());
    }
}
