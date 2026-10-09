package com.otilm.np.webhook.service.impl;

import com.otilm.api.exception.ValidationError;
import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.client.attribute.RequestAttribute;
import com.otilm.api.model.common.attribute.common.BaseAttribute;
import com.otilm.core.util.AttributeDefinitionUtils;
import com.otilm.np.webhook.attribute.Attributes;
import com.otilm.np.webhook.attribute.ContentType;
import com.otilm.np.webhook.service.AttributeService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AttributeServiceImpl implements AttributeService {

    private static final Logger logger = LoggerFactory.getLogger(AttributeServiceImpl.class);

    private static final String KIND_WEBHOOK = "WEBHOOK";
    private static final String UNSUPPORTED_KIND_MESSAGE = "Unsupported kind {}";

    @Override
    public List<BaseAttribute> getAttributes(String kind) {
        logger.debug("Getting the attributes for {}", kind);

        if (!KIND_WEBHOOK.equals(kind)) {
            throw new ValidationException(ValidationError.create(UNSUPPORTED_KIND_MESSAGE, kind));
        }

        List<BaseAttribute> attributes = new ArrayList<>();
        attributes.add(Attributes.dataWebhookUrl());
        attributes.add(Attributes.infoContentType());
        attributes.add(Attributes.dataContentType());
        attributes.add(Attributes.groupContentTemplate());

        return attributes;
    }

    public List<BaseAttribute> getAllDataAttributes(String kind, ContentType contentType) {
        if (!KIND_WEBHOOK.equals(kind)) {
            throw new ValidationException(ValidationError.create(UNSUPPORTED_KIND_MESSAGE, kind));
        }

        List<BaseAttribute> attributes = new ArrayList<>();
        attributes.add(Attributes.dataWebhookUrl());
        attributes.add(Attributes.dataContentType());
        attributes.add(Attributes.dataContentTemplate(contentType));

        return attributes;
    }

    @Override
    public boolean validateAttributes(String kind, List<RequestAttribute> attributes) {
        logger.debug("Validating the attributes for kind {} with attributes: {}", kind, attributes);

        if (!KIND_WEBHOOK.equals(kind)) {
            throw new ValidationException(ValidationError.create(UNSUPPORTED_KIND_MESSAGE, kind));
        }
        if (attributes == null) {
            return false;
        }

        AttributeDefinitionUtils.validateAttributes(getAttributes(kind), attributes);
        return true;
    }

}
