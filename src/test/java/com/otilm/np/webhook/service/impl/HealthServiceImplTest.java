package com.otilm.np.webhook.service.impl;

import com.otilm.api.model.common.HealthDto;
import com.otilm.api.model.common.HealthStatus;
import com.otilm.np.webhook.service.NotificationInstanceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceImplTest {

    private static final String DATABASE_PART = "database";

    @Mock
    private NotificationInstanceService notificationInstanceService;

    private HealthServiceImpl healthService;

    @BeforeEach
    void setUp() {
        healthService = new HealthServiceImpl();
        healthService.setNotificationInstanceService(notificationInstanceService);
    }

    @Test
    void checkHealth_reportsOkWhenTheDatabaseAnswers() {
        when(notificationInstanceService.listNotificationInstances()).thenReturn(List.of());

        HealthDto health = healthService.checkHealth();

        assertEquals(HealthStatus.OK, health.getStatus());
        assertEquals(HealthStatus.OK, health.getParts().get(DATABASE_PART).getStatus());
        assertEquals("Database connection ok", health.getParts().get(DATABASE_PART).getDescription());
    }

    /** A failing part has to surface in the overall status, which is what the platform polls. */
    @Test
    void checkHealth_reportsNotOkWhenTheDatabaseFails() {
        when(notificationInstanceService.listNotificationInstances())
                .thenThrow(new IllegalStateException("connection refused"));

        HealthDto health = healthService.checkHealth();

        assertEquals(HealthStatus.NOK, health.getStatus());
        HealthDto database = health.getParts().get(DATABASE_PART);
        assertEquals(HealthStatus.NOK, database.getStatus());
        assertEquals("connection refused", database.getDescription());
    }
}
