package com.otilm.np.webhook.service.impl;

import com.otilm.api.model.common.HealthDto;
import com.otilm.api.model.common.HealthStatus;
import com.otilm.np.webhook.service.HealthService;
import com.otilm.np.webhook.service.NotificationInstanceService;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {

    private static final Logger logger = LoggerFactory.getLogger(HealthServiceImpl.class);

    private NotificationInstanceService notificationInstanceService;

    @Autowired
    public void setNotificationInstanceService(NotificationInstanceService notificationInstanceService) {
        this.notificationInstanceService = notificationInstanceService;
    }

    @Override
    public HealthDto checkHealth() {
        HealthDto health = new HealthDto();

        Map<String, HealthDto> parts = new HashMap<>();
        parts.put("database", checkDbStatus());

        health.setParts(parts);

        // set the overall status
        health.setStatus(HealthStatus.OK);
        for (var entry : health.getParts().entrySet()) {
            if (entry.getValue().getStatus() == HealthStatus.NOK) {
                health.setStatus(HealthStatus.NOK);
                break;
            }
        }
        return health;
    }

    private HealthDto checkDbStatus() {
        HealthDto h = new HealthDto();
        try {
            notificationInstanceService.listNotificationInstances();
            h.setStatus(HealthStatus.OK);
            h.setDescription("Database connection ok");
        } catch (Exception e) {
            logger.debug("Database connection failed: {}", String.valueOf(e));
            h.setStatus(HealthStatus.NOK);
            h.setDescription(e.getMessage());
        }
        return h;
    }

}
