package com.otilm.np.webhook.api;

import com.otilm.api.exception.AlreadyExistException;
import com.otilm.api.exception.NotFoundException;
import com.otilm.api.exception.ValidationException;
import com.otilm.api.interfaces.connector.NotificationInstanceController;
import com.otilm.api.model.common.attribute.common.DataAttribute;
import com.otilm.api.model.connector.notification.NotificationProviderInstanceDto;
import com.otilm.api.model.connector.notification.NotificationProviderInstanceRequestDto;
import com.otilm.api.model.connector.notification.NotificationProviderNotifyRequestDto;
import com.otilm.np.webhook.service.AttributeService;
import com.otilm.np.webhook.service.NotificationInstanceService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationInstanceControllerImpl implements NotificationInstanceController {

    private NotificationInstanceService notificationInstanceService;
    private AttributeService attributeService;

    @Autowired
    public void setNotificationInstanceService(NotificationInstanceService notificationInstanceService) {
        this.notificationInstanceService = notificationInstanceService;
    }

    @Autowired
    public void setAttributeService(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @Override
    public List<NotificationProviderInstanceDto> listNotificationInstances() {
        return notificationInstanceService.listNotificationInstances();
    }

    @Override
    public NotificationProviderInstanceDto getNotificationInstance(String uuid) throws NotFoundException {
        return notificationInstanceService.getNotificationInstance(UUID.fromString(uuid));
    }

    @Override
    public NotificationProviderInstanceDto createNotificationInstance(NotificationProviderInstanceRequestDto request)
            throws AlreadyExistException {
        if (!attributeService.validateAttributes(request.getKind(), request.getAttributes())) {
            throw new ValidationException("Notification instance attributes validation failed.");
        }
        return notificationInstanceService.createNotificationInstance(request);
    }

    @Override
    public NotificationProviderInstanceDto updateNotificationInstance(String uuid,
            NotificationProviderInstanceRequestDto request) throws NotFoundException {
        if (!attributeService.validateAttributes(request.getKind(), request.getAttributes())) {
            throw new ValidationException("Notification instance attributes validation failed.");
        }
        return notificationInstanceService.updateNotificationInstance(UUID.fromString(uuid), request);
    }

    @Override
    public void removeNotificationInstance(String uuid) throws NotFoundException {
        notificationInstanceService.removeNotificationInstance(UUID.fromString(uuid));
    }

    @Override
    public void sendNotification(String uuid, NotificationProviderNotifyRequestDto request) throws NotFoundException {
        notificationInstanceService.sendNotification(UUID.fromString(uuid), request);
    }

    @Override
    public List<DataAttribute> listMappingAttributes(String kind) {
        // return an empty list as we do not need any custom attributes
        return List.of();
    }
}
