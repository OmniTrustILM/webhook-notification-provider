package com.otilm.np.webhook.attribute;

import com.otilm.api.model.common.attribute.common.AttributeType;
import com.otilm.api.model.common.attribute.common.callback.AttributeCallback;
import com.otilm.api.model.common.attribute.common.callback.AttributeCallbackMapping;
import com.otilm.api.model.common.attribute.common.callback.AttributeValueTarget;
import com.otilm.api.model.common.attribute.common.constraint.BaseAttributeConstraint;
import com.otilm.api.model.common.attribute.common.constraint.RegexpAttributeConstraint;
import com.otilm.api.model.common.attribute.common.content.AttributeContentType;
import com.otilm.api.model.common.attribute.common.properties.DataAttributeProperties;
import com.otilm.api.model.common.attribute.v2.DataAttributeV2;
import com.otilm.api.model.common.attribute.v2.GroupAttributeV2;
import com.otilm.api.model.common.attribute.v2.InfoAttributeV2;
import com.otilm.api.model.common.attribute.v2.content.BaseAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.CodeBlockAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.StringAttributeContentV2;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform identifies attributes by UUID and name, so these are part of the connector's contract: a changed
 * identifier orphans the configuration of every existing notification instance.
 */
class AttributesTest {

    /**
     * The platform stores attributes by UUID and name, so these literals are the connector's contract rather than a
     * restatement of the constants: changing either orphans the configuration of every existing notification instance.
     */
    @Test
    void attributeIdentifiersMatchTheEstablishedValues() {
        assertEquals("3b8a11b3-a59d-427c-9491-56c8ce27cee7", Attributes.DATA_WEBHOOK_URL_UUID);
        assertEquals("data_webhookUrl", Attributes.DATA_WEBHOOK_URL_NAME);
        assertEquals("b104d74d-8a54-4aa3-9e00-9c535f8bb80c", Attributes.DATA_CONTENT_TYPE_UUID);
        assertEquals("data_contentType", Attributes.DATA_CONTENT_TYPE_NAME);
        assertEquals("f3b9ae81-279b-4886-a097-a8e08c2c356b", Attributes.GROUP_CONTENT_TEMPLATE_UUID);
        assertEquals("group_contentTemplate", Attributes.GROUP_CONTENT_TEMPLATE_NAME);
        assertEquals("5f2ef01f-4cc3-441e-be78-5dfdf2c4c2d9", Attributes.INFO_CONTENT_TYPE_UUID);
        assertEquals("info_contentType", Attributes.INFO_CONTENT_TYPE_NAME);
        assertEquals("data_contentTemplate", Attributes.DATA_CONTENT_TEMPLATE_NAME);
        assertEquals("/v1/notificationProvider/callbacks/template/{contentType}/attributes",
                Attributes.CONTENT_TEMPLATE_CALLBACK_CONTEXT);
    }

    @Test
    void infoContentTypeDescribesEverySupportedContentType() {
        InfoAttributeV2 attribute = Attributes.infoContentType();

        assertEquals(Attributes.INFO_CONTENT_TYPE_UUID, attribute.getUuid());
        assertEquals(Attributes.INFO_CONTENT_TYPE_NAME, attribute.getName());
        assertEquals(Attributes.INFO_CONTENT_TYPE_DESCRIPTION, attribute.getDescription());
        assertEquals(AttributeType.INFO, attribute.getType());
        assertEquals(AttributeContentType.TEXT, attribute.getContentType());
        assertEquals(Attributes.INFO_CONTENT_TYPE_LABEL, attribute.getProperties().getLabel());
        assertTrue(attribute.getProperties().isVisible());

        String content = String.valueOf(attribute.getContent().get(0).getData());
        for (ContentType contentType : ContentType.values()) {
            assertTrue(content.contains(contentType.name()),
                    "the operator-facing description must document " + contentType.name());
        }
    }

    @Test
    void dataWebhookUrlIsRequiredAndConstrainedToHttpUrls() {
        DataAttributeV2 attribute = Attributes.dataWebhookUrl();

        assertEquals(Attributes.DATA_WEBHOOK_URL_UUID, attribute.getUuid());
        assertEquals(Attributes.DATA_WEBHOOK_URL_NAME, attribute.getName());
        assertEquals(Attributes.DATA_WEBHOOK_URL_DESCRIPTION, attribute.getDescription());
        assertEquals(AttributeContentType.STRING, attribute.getContentType());
        assertEquals(AttributeType.DATA, attribute.getType());

        DataAttributeProperties properties = attribute.getProperties();
        assertEquals(Attributes.DATA_WEBHOOK_URL_LABEL, properties.getLabel());
        assertTrue(properties.isRequired());
        assertFalse(properties.isReadOnly());
        assertTrue(properties.isVisible());
        assertFalse(properties.isList());
        assertFalse(properties.isMultiSelect());

        List<BaseAttributeConstraint<?>> constraints = attribute.getConstraints();
        assertEquals(1, constraints.size());
        RegexpAttributeConstraint constraint = assertInstanceOf(RegexpAttributeConstraint.class, constraints.get(0));
        assertEquals("https?://.*", constraint.getData());
        assertTrue("https://example.com/webhook".matches(constraint.getData()));
        assertFalse("ftp://example.com/webhook".matches(constraint.getData()));
    }

    @Test
    void dataContentTypeOffersEverySupportedContentTypeAsAList() {
        DataAttributeV2 attribute = Attributes.dataContentType();

        assertEquals(Attributes.DATA_CONTENT_TYPE_UUID, attribute.getUuid());
        assertEquals(Attributes.DATA_CONTENT_TYPE_NAME, attribute.getName());
        assertEquals(AttributeContentType.STRING, attribute.getContentType());
        assertTrue(attribute.getProperties().isList());
        assertFalse(attribute.getProperties().isMultiSelect());

        List<BaseAttributeContentV2<?>> content = attribute.getContent();
        assertEquals(ContentType.values().length, content.size());

        // The reference carries the wire name of the content type, the data its enum name; the
        // instance configuration is resolved back to a ContentType from the data.
        List<String> references = content
                .stream()
                .map(item -> assertInstanceOf(StringAttributeContentV2.class, item).getReference())
                .toList();
        assertEquals(Arrays.stream(ContentType.values()).map(ContentType::getContentType).toList(), references);

        List<String> data = content.stream().map(item -> String.valueOf(item.getData())).toList();
        assertEquals(Arrays.stream(ContentType.values()).map(ContentType::name).toList(), data);

        for (String value : data) {
            assertEquals(ContentType.valueOf(value), ContentType.fromContentType(value),
                    "the offered content value must resolve back to its content type");
        }
    }

    @Test
    void groupContentTemplateCallsBackWithTheSelectedContentType() {
        GroupAttributeV2 attribute = Attributes.groupContentTemplate();

        assertEquals(Attributes.GROUP_CONTENT_TEMPLATE_UUID, attribute.getUuid());
        assertEquals(Attributes.GROUP_CONTENT_TEMPLATE_NAME, attribute.getName());
        assertEquals(Attributes.GROUP_CONTENT_TEMPLATE_DESCRIPTION, attribute.getDescription());
        assertEquals(AttributeType.GROUP, attribute.getType());

        AttributeCallback callback = attribute.getAttributeCallback();
        assertEquals(Attributes.CONTENT_TEMPLATE_CALLBACK_CONTEXT, callback.getCallbackContext());
        assertEquals("GET", callback.getCallbackMethod());

        Set<AttributeCallbackMapping> mappings = callback.getMappings();
        assertEquals(1, mappings.size());
        AttributeCallbackMapping mapping = mappings.iterator().next();
        assertEquals(Attributes.DATA_CONTENT_TYPE_NAME + ".data", mapping.getFrom());
        assertEquals("contentType", mapping.getTo());
        assertTrue(mapping.getTargets().contains(AttributeValueTarget.PATH_VARIABLE));
    }

    @ParameterizedTest
    @EnumSource(ContentType.class)
    void dataContentTemplateIsScopedToItsContentType(ContentType contentType) {
        DataAttributeV2 attribute = Attributes.dataContentTemplate(contentType);

        assertEquals(Attributes.getDataContentTemplateUuid(contentType), attribute.getUuid());
        assertEquals(Attributes.getDataContentTemplateName(contentType), attribute.getName());
        assertEquals(Attributes.DATA_CONTENT_TEMPLATE_DESCRIPTION, attribute.getDescription());
        assertEquals(AttributeContentType.CODEBLOCK, attribute.getContentType());
        assertEquals(AttributeType.DATA, attribute.getType());
        assertTrue(attribute.getProperties().isRequired());
        assertFalse(attribute.getProperties().isList());

        CodeBlockAttributeContentV2 content = assertInstanceOf(CodeBlockAttributeContentV2.class,
                attribute.getContent().get(0));
        assertEquals(contentType.getLanguage(), content.getData().getLanguage());
    }

    /**
     * The template attribute identifiers are derived, but they still identify attributes in the platform database, so
     * they are pinned here to the values the connector has always produced. A change to the derivation would orphan the
     * template of every configured instance.
     */
    @ParameterizedTest
    @CsvSource({
            "RAW_JSON, 07ea053b-3515-3ce8-a8a5-acfca3d4ed15, data_contentTemplate_raw_json",
            "JSON,     77ddf598-724a-31d4-943b-79ee71c854c5, data_contentTemplate_json",
            "XML,      610ed4fa-0b1f-33a9-9a31-0be332673a58, data_contentTemplate_xml",})
    void derivedTemplateIdentifiersMatchTheEstablishedValues(ContentType contentType, String uuid, String name) {
        assertEquals(uuid, Attributes.getDataContentTemplateUuid(contentType));
        assertEquals(name, Attributes.getDataContentTemplateName(contentType));
        assertEquals(uuid, Attributes.dataContentTemplate(contentType).getUuid());
        assertEquals(name, Attributes.dataContentTemplate(contentType).getName());
    }

    @Test
    void derivedTemplateIdentifiersDifferBetweenContentTypes() {
        assertNotEquals(Attributes.getDataContentTemplateUuid(ContentType.JSON),
                Attributes.getDataContentTemplateUuid(ContentType.XML));

        Set<String> uuids = Arrays
                .stream(ContentType.values())
                .map(Attributes::getDataContentTemplateUuid)
                .collect(Collectors.toSet());
        assertEquals(ContentType.values().length, uuids.size(), "each content type needs its own attribute UUID");
    }
}
