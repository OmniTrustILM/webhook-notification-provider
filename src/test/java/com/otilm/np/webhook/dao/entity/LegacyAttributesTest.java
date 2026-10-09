package com.otilm.np.webhook.dao.entity;

import com.otilm.api.model.common.attribute.common.AttributeType;
import com.otilm.api.model.common.attribute.common.BaseAttribute;
import com.otilm.api.model.common.attribute.common.DataAttribute;
import com.otilm.api.model.common.attribute.common.content.AttributeContentType;
import com.otilm.api.model.common.attribute.common.content.data.CodeBlockAttributeContentData;
import com.otilm.api.model.common.attribute.common.content.data.ProgrammingLanguageEnum;
import com.otilm.api.model.common.attribute.v2.content.CodeBlockAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.StringAttributeContentV2;
import com.otilm.core.util.AttributeDefinitionUtils;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The attributes of a notification instance are stored as serialized JSON, so rows written by earlier releases have to
 * keep deserializing after the library upgrade.
 *
 * <p>
 * The payload below was produced by serializing this connector's own attribute definitions against the pre-rebrand
 * interfaces release, for a webhook configured with a URL and a JSON content template. It records that the serialized
 * form carries a logical type discriminator and a version rather than class names, which is why the namespace change
 * needs no data migration.
 * </p>
 */
class LegacyAttributesTest {

    private static final String LEGACY_ATTRIBUTES = """
            [{"version":2,"uuid":"3b8a11b3-a59d-427c-9491-56c8ce27cee7","name":"data_webhookUrl",\
            "description":"Webhook URL to send the event data to",\
            "content":[{"reference":"https://example.com/webhook","data":"https://example.com/webhook"}],\
            "type":"data","contentType":"string","properties":{"label":"Webhook URL","visible":true,\
            "group":null,"required":true,"readOnly":false,"list":false,"multiSelect":false}},\
            {"version":2,"uuid":"77ddf598-724a-31d4-943b-79ee71c854c5","name":"data_contentTemplate_json",\
            "content":[{"reference":null,"data":{"language":"json","code":"eyJldmVudCI6ICIke2V2ZW50fSJ9"}}],\
            "type":"data","contentType":"codeblock","properties":{"label":"Content template","visible":true,\
            "group":null,"required":true,"readOnly":false,"list":false,"multiSelect":false}}]""";

    @Test
    void attributesStoredByThePreRebrandReleaseStillDeserialize() {
        List<BaseAttribute> attributes = AttributeDefinitionUtils.deserialize(LEGACY_ATTRIBUTES, BaseAttribute.class);

        assertEquals(2, attributes.size());
        assertEquals(List.of("data_webhookUrl", "data_contentTemplate_json"),
                attributes.stream().map(BaseAttribute::getName).toList());

        DataAttribute url = assertInstanceOf(DataAttribute.class, attributes.get(0));
        assertEquals("3b8a11b3-a59d-427c-9491-56c8ce27cee7", url.getUuid());
        assertEquals(AttributeType.DATA, url.getType());
        assertEquals(AttributeContentType.STRING, url.getContentType());
        assertEquals("Webhook URL", url.getProperties().getLabel());

        DataAttribute template = assertInstanceOf(DataAttribute.class, attributes.get(1));
        assertEquals(AttributeContentType.CODEBLOCK, template.getContentType());
    }

    /**
     * The content of a stored instance is read back to rebuild its configuration, so the values themselves have to
     * survive, not just the attribute envelope.
     */
    @Test
    void contentOfLegacyAttributesIsPreserved() {
        List<BaseAttribute> attributes = AttributeDefinitionUtils.deserialize(LEGACY_ATTRIBUTES, BaseAttribute.class);

        String url = AttributeDefinitionUtils
                .getSingleItemAttributeContentValue("data_webhookUrl", attributes, StringAttributeContentV2.class)
                .getData();
        assertEquals("https://example.com/webhook", url);

        CodeBlockAttributeContentData template = AttributeDefinitionUtils
                .getSingleItemAttributeContentValue("data_contentTemplate_json", attributes,
                        CodeBlockAttributeContentV2.class)
                .getData();
        assertEquals(ProgrammingLanguageEnum.JSON, template.getLanguage());
        assertEquals("eyJldmVudCI6ICIke2V2ZW50fSJ9", template.getCode());
    }
}
