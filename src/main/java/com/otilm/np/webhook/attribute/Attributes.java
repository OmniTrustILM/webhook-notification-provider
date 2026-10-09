package com.otilm.np.webhook.attribute;

import com.otilm.api.model.common.attribute.common.AttributeType;
import com.otilm.api.model.common.attribute.common.callback.AttributeCallback;
import com.otilm.api.model.common.attribute.common.callback.AttributeCallbackMapping;
import com.otilm.api.model.common.attribute.common.callback.AttributeValueTarget;
import com.otilm.api.model.common.attribute.common.constraint.RegexpAttributeConstraint;
import com.otilm.api.model.common.attribute.common.content.AttributeContentType;
import com.otilm.api.model.common.attribute.common.content.data.CodeBlockAttributeContentData;
import com.otilm.api.model.common.attribute.common.properties.DataAttributeProperties;
import com.otilm.api.model.common.attribute.common.properties.InfoAttributeProperties;
import com.otilm.api.model.common.attribute.v2.DataAttributeV2;
import com.otilm.api.model.common.attribute.v2.GroupAttributeV2;
import com.otilm.api.model.common.attribute.v2.InfoAttributeV2;
import com.otilm.api.model.common.attribute.v2.content.CodeBlockAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.StringAttributeContentV2;
import com.otilm.api.model.common.attribute.v2.content.TextAttributeContentV2;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Attributes {

    public static final String DATA_WEBHOOK_URL_UUID = "3b8a11b3-a59d-427c-9491-56c8ce27cee7";
    public static final String DATA_WEBHOOK_URL_NAME = "data_webhookUrl";
    public static final String DATA_WEBHOOK_URL_DESCRIPTION = "Webhook URL to send the event data to";
    public static final String DATA_WEBHOOK_URL_LABEL = "Webhook URL";

    public static final String DATA_CONTENT_TYPE_UUID = "b104d74d-8a54-4aa3-9e00-9c535f8bb80c";
    public static final String DATA_CONTENT_TYPE_NAME = "data_contentType";
    public static final String DATA_CONTENT_TYPE_DESCRIPTION = "Content type of the data to be sent";
    public static final String DATA_CONTENT_TYPE_LABEL = "Content type";

    public static final String GROUP_CONTENT_TEMPLATE_UUID = "f3b9ae81-279b-4886-a097-a8e08c2c356b";
    public static final String GROUP_CONTENT_TEMPLATE_NAME = "group_contentTemplate";
    public static final String GROUP_CONTENT_TEMPLATE_DESCRIPTION = "Content template for the webhook to be sent in selected type";

    public static final String DATA_CONTENT_TEMPLATE_UUID = "1b247b77-e9c0-45b9-8114-06377cbeedc7";
    public static final String DATA_CONTENT_TEMPLATE_NAME = "data_contentTemplate";
    public static final String DATA_CONTENT_TEMPLATE_DESCRIPTION = "Content template of the data to be sent";
    public static final String DATA_CONTENT_TEMPLATE_LABEL = "Content template";

    public static final String INFO_CONTENT_TYPE_UUID = "5f2ef01f-4cc3-441e-be78-5dfdf2c4c2d9";
    public static final String INFO_CONTENT_TYPE_NAME = "info_contentType";
    public static final String INFO_CONTENT_TYPE_DESCRIPTION = "Information about the content type";
    public static final String INFO_CONTENT_TYPE_LABEL = "Content type information";

    /** Callback that resolves the template attribute for the selected content type. */
    public static final String CONTENT_TEMPLATE_CALLBACK_CONTEXT = "/v1/notificationProvider/callbacks/template/{contentType}/attributes";

    private Attributes() {
    }

    public static InfoAttributeV2 infoContentType() {
        InfoAttributeV2 attribute = new InfoAttributeV2();

        attribute.setUuid(INFO_CONTENT_TYPE_UUID);
        attribute.setName(INFO_CONTENT_TYPE_NAME);
        attribute.setDescription(INFO_CONTENT_TYPE_DESCRIPTION);
        attribute.setType(AttributeType.INFO);
        attribute.setContentType(AttributeContentType.TEXT);

        InfoAttributeProperties properties = new InfoAttributeProperties();
        properties.setLabel(INFO_CONTENT_TYPE_LABEL);
        properties.setVisible(true);
        attribute.setProperties(properties);

        String content = """
                The content type of the data to be sent to the webhook. The following content types are supported:
                - `RAW_JSON` - will send the data as raw JSON to the specified webhook URL
                - `JSON` - prepares the data in JSON format according to the specified template
                - `XML` - prepares the data in XML format according to the specified template

                The template support FreeMarker syntax with variables that can be used to build the content dynamically.
                """;

        attribute.setContent(List.of(new TextAttributeContentV2(content)));

        return attribute;
    }

    public static DataAttributeV2 dataWebhookUrl() {
        DataAttributeV2 attribute = dataAttribute(DATA_WEBHOOK_URL_UUID, DATA_WEBHOOK_URL_NAME,
                DATA_WEBHOOK_URL_DESCRIPTION, AttributeContentType.STRING, DATA_WEBHOOK_URL_LABEL, false);

        attribute.setContent(List.of(new StringAttributeContentV2("https://example.com/webhook")));

        RegexpAttributeConstraint urlConstraint = new RegexpAttributeConstraint();
        urlConstraint.setDescription(DATA_WEBHOOK_URL_LABEL);
        urlConstraint.setErrorMessage("Invalid webhook URL format");
        urlConstraint.setData("https?://.*");
        attribute.setConstraints(List.of(urlConstraint));

        return attribute;
    }

    public static DataAttributeV2 dataContentType() {
        DataAttributeV2 attribute = dataAttribute(DATA_CONTENT_TYPE_UUID, DATA_CONTENT_TYPE_NAME,
                DATA_CONTENT_TYPE_DESCRIPTION, AttributeContentType.STRING, DATA_CONTENT_TYPE_LABEL, true);

        attribute
                .setContent(Arrays
                        .stream(ContentType.values())
                        .map(type -> new StringAttributeContentV2(type.getContentType(), type.name()))
                        .toList());

        return attribute;
    }

    public static GroupAttributeV2 groupContentTemplate() {
        GroupAttributeV2 attribute = new GroupAttributeV2();

        attribute.setUuid(GROUP_CONTENT_TEMPLATE_UUID);
        attribute.setName(GROUP_CONTENT_TEMPLATE_NAME);
        attribute.setDescription(GROUP_CONTENT_TEMPLATE_DESCRIPTION);
        attribute.setType(AttributeType.GROUP);

        Set<AttributeCallbackMapping> mappings = new HashSet<>();
        mappings
                .add(new AttributeCallbackMapping(DATA_CONTENT_TYPE_NAME + ".data", "contentType",
                        AttributeValueTarget.PATH_VARIABLE));

        AttributeCallback attributeCallback = new AttributeCallback();
        attributeCallback.setCallbackContext(CONTENT_TEMPLATE_CALLBACK_CONTEXT);
        attributeCallback.setCallbackMethod("GET");
        attributeCallback.setMappings(mappings);
        attribute.setAttributeCallback(attributeCallback);

        return attribute;
    }

    public static DataAttributeV2 dataContentTemplate(ContentType contentType) {
        // UUID and name must be unique per content type, so both are derived from it
        DataAttributeV2 attribute = dataAttribute(getDataContentTemplateUuid(contentType),
                getDataContentTemplateName(contentType), DATA_CONTENT_TEMPLATE_DESCRIPTION,
                AttributeContentType.CODEBLOCK, DATA_CONTENT_TEMPLATE_LABEL, false);

        CodeBlockAttributeContentData data = new CodeBlockAttributeContentData();
        data.setLanguage(contentType.getLanguage());

        CodeBlockAttributeContentV2 attributeContent = new CodeBlockAttributeContentV2();
        attributeContent.setData(data);
        attribute.setContent(List.of(attributeContent));

        return attribute;
    }

    public static String getDataContentTemplateUuid(ContentType contentType) {
        return UUID
                .nameUUIDFromBytes((DATA_CONTENT_TEMPLATE_UUID + contentType.getContentType()).getBytes())
                .toString();
    }

    public static String getDataContentTemplateName(ContentType contentType) {
        return DATA_CONTENT_TEMPLATE_NAME + "_" + contentType.getContentType();
    }

    /**
     * Skeleton shared by every data attribute of this provider. All of them are required, visible and single-select;
     * only the list flag varies, so it stays a parameter.
     */
    private static DataAttributeV2 dataAttribute(String uuid, String name, String description,
            AttributeContentType contentType, String label, boolean list) {
        DataAttributeV2 attribute = new DataAttributeV2();

        attribute.setUuid(uuid);
        attribute.setName(name);
        attribute.setDescription(description);
        attribute.setContentType(contentType);
        attribute.setType(AttributeType.DATA);

        DataAttributeProperties properties = new DataAttributeProperties();
        properties.setLabel(label);
        properties.setRequired(true);
        properties.setReadOnly(false);
        properties.setVisible(true);
        properties.setList(list);
        properties.setMultiSelect(false);
        attribute.setProperties(properties);

        return attribute;
    }

}
