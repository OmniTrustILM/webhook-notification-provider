package com.otilm.np.webhook.service.impl;

import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.client.attribute.RequestAttribute;
import com.otilm.api.model.client.attribute.RequestAttributeV2;
import com.otilm.api.model.common.attribute.common.BaseAttribute;
import com.otilm.api.model.common.attribute.common.content.AttributeContentType;
import com.otilm.api.model.common.attribute.common.content.data.CodeBlockAttributeContentData;
import com.otilm.api.model.common.attribute.v2.content.BaseAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.CodeBlockAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.StringAttributeContentV2;
import com.otilm.np.webhook.attribute.Attributes;
import com.otilm.np.webhook.attribute.ContentType;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeServiceImplTest {

    private static final String WEBHOOK_KIND = "WEBHOOK";
    private static final String UNSUPPORTED_KIND = "EMAIL";

    private AttributeServiceImpl attributeService;

    @BeforeEach
    void setUp() {
        attributeService = new AttributeServiceImpl();
    }

    /**
     * The definition list is what the platform renders when an instance is configured: the URL and content type first,
     * then the group attribute whose callback supplies the matching template.
     */
    @Test
    void getAttributes_describesTheInstanceConfigurationForm() {
        List<BaseAttribute> attributes = attributeService.getAttributes(WEBHOOK_KIND);

        assertEquals(
                List
                        .of(Attributes.DATA_WEBHOOK_URL_NAME, Attributes.INFO_CONTENT_TYPE_NAME,
                                Attributes.DATA_CONTENT_TYPE_NAME, Attributes.GROUP_CONTENT_TEMPLATE_NAME),
                attributes.stream().map(BaseAttribute::getName).toList());
    }

    @Test
    void getAttributes_rejectsUnsupportedKind() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> attributeService.getAttributes(UNSUPPORTED_KIND));
        assertNotNull(ex.getMessage());
    }

    /**
     * The stored data attributes resolve the group attribute into the concrete template attribute of the selected
     * content type, so the persisted set differs from the definition list.
     */
    @ParameterizedTest
    @EnumSource(ContentType.class)
    void getAllDataAttributes_resolvesTheTemplateForTheSelectedContentType(ContentType contentType) {
        List<BaseAttribute> attributes = attributeService.getAllDataAttributes(WEBHOOK_KIND, contentType);

        assertEquals(
                List
                        .of(Attributes.DATA_WEBHOOK_URL_NAME, Attributes.DATA_CONTENT_TYPE_NAME,
                                Attributes.getDataContentTemplateName(contentType)),
                attributes.stream().map(BaseAttribute::getName).toList());
    }

    @Test
    void getAllDataAttributes_rejectsUnsupportedKind() {
        assertThrows(ValidationException.class,
                () -> attributeService.getAllDataAttributes(UNSUPPORTED_KIND, ContentType.JSON));
    }

    @Test
    void validateAttributes_acceptsACompleteConfiguration() {
        assertTrue(attributeService.validateAttributes(WEBHOOK_KIND, validRequestAttributes()));
    }

    /** A request without attributes is reported as invalid rather than raising. */
    @Test
    void validateAttributes_reportsMissingAttributesAsInvalid() {
        assertFalse(attributeService.validateAttributes(WEBHOOK_KIND, null));
    }

    @Test
    void validateAttributes_rejectsRequiredAttributesLeftOut() {
        List<RequestAttribute> empty = List.of();

        assertThrows(ValidationException.class, () -> attributeService.validateAttributes(WEBHOOK_KIND, empty));
    }

    @Test
    void validateAttributes_rejectsUnsupportedKind() {
        List<RequestAttribute> attributes = validRequestAttributes();

        assertThrows(ValidationException.class,
                () -> attributeService.validateAttributes(UNSUPPORTED_KIND, attributes));
    }

    @Test
    void validateAttributes_rejectsAWebhookUrlThatBreaksTheConstraint() {
        List<RequestAttribute> attributes = List
                .of(stringAttribute(Attributes.DATA_WEBHOOK_URL_UUID, Attributes.DATA_WEBHOOK_URL_NAME,
                        "ftp://example.com"),
                        stringAttribute(Attributes.DATA_CONTENT_TYPE_UUID, Attributes.DATA_CONTENT_TYPE_NAME,
                                ContentType.JSON.name()));

        assertThrows(ValidationException.class, () -> attributeService.validateAttributes(WEBHOOK_KIND, attributes));
    }

    private List<RequestAttribute> validRequestAttributes() {
        CodeBlockAttributeContentV2 template = new CodeBlockAttributeContentV2();
        template
                .setData(new CodeBlockAttributeContentData(ContentType.JSON.getLanguage(),
                        Base64.getEncoder().encodeToString("{\"event\": \"${event}\"}".getBytes())));

        RequestAttributeV2 contentTemplate = new RequestAttributeV2();
        contentTemplate.setUuid(UUID.fromString(Attributes.getDataContentTemplateUuid(ContentType.JSON)));
        contentTemplate.setName(Attributes.getDataContentTemplateName(ContentType.JSON));
        contentTemplate.setContentType(AttributeContentType.CODEBLOCK);
        contentTemplate.setContent(List.<BaseAttributeContentV2<?>>of(template));

        return List
                .of(stringAttribute(Attributes.DATA_WEBHOOK_URL_UUID, Attributes.DATA_WEBHOOK_URL_NAME,
                        "https://example.com/webhook"),
                        stringAttribute(Attributes.DATA_CONTENT_TYPE_UUID, Attributes.DATA_CONTENT_TYPE_NAME,
                                ContentType.JSON.name()),
                        contentTemplate);
    }

    private RequestAttributeV2 stringAttribute(String uuid, String name, String value) {
        RequestAttributeV2 attribute = new RequestAttributeV2();
        attribute.setUuid(UUID.fromString(uuid));
        attribute.setName(name);
        attribute.setContentType(AttributeContentType.STRING);
        attribute.setContent(List.<BaseAttributeContentV2<?>>of(new StringAttributeContentV2(value)));
        return attribute;
    }
}
