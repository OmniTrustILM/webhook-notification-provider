package com.otilm.np.webhook;

import com.otilm.np.webhook.attribute.ContentType;
import com.otilm.np.webhook.dao.entity.NotificationInstance;
import com.otilm.np.webhook.dao.repository.NotificationInstanceRepository;
import com.otilm.np.webhook.util.TemplateUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reports the stored content templates that will not parse, once, at startup. Templates saved before they were checked
 * on save may not, and without this an operator would learn of it from the first notification that failed to arrive.
 */
@Component
public class ContentTemplatePreflight {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentTemplatePreflight.class);

    private final NotificationInstanceRepository notificationInstanceRepository;

    public ContentTemplatePreflight(NotificationInstanceRepository notificationInstanceRepository) {
        this.notificationInstanceRepository = notificationInstanceRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportUnrenderableTemplates() {
        List<String> unrenderable;
        try {
            unrenderable = unrenderableTemplates();
        } catch (RuntimeException e) {
            // The check only reports, so a store it cannot read must not keep the connector from starting
            LOGGER.warn("Stored content templates could not be checked: {}", e.getClass().getSimpleName());
            return;
        }
        for (String instanceAndReason : unrenderable) {
            LOGGER.warn("Notification instance {}", instanceAndReason);
        }
    }

    /** One entry per instance whose content template will not parse, naming the instance and why. */
    List<String> unrenderableTemplates() {
        List<String> reported = new ArrayList<>();
        for (NotificationInstance instance : notificationInstanceRepository.findAll()) {
            if (instance.getContentType() != ContentType.RAW_JSON) {
                failureOf(instance).ifPresent(reason -> reported.add("'%s': %s".formatted(instance.getName(), reason)));
            }
        }
        return reported;
    }

    private Optional<String> failureOf(NotificationInstance instance) {
        try {
            return TemplateUtils.contentTemplateFailure(instance.getContentTemplate());
        } catch (RuntimeException e) {
            // A template that cannot even be read is worth naming too, and must not stop the startup of the rest
            return Optional.of(e.getClass().getSimpleName());
        }
    }
}
