package com.otilm.np.webhook.attribute;

import com.otilm.api.exception.ValidationError;
import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.common.attribute.common.content.data.ProgrammingLanguageEnum;

import java.util.Arrays;

public enum ContentType {
    RAW_JSON("raw_json", "application/json", ProgrammingLanguageEnum.JSON),
    JSON("json", "application/json", ProgrammingLanguageEnum.JSON),
    XML("xml", "application/xml", ProgrammingLanguageEnum.XML);

    private static final ContentType[] VALUES;

    static {
        VALUES = values();
    }

    private final String code;
    private final String contentHeader;
    private final ProgrammingLanguageEnum language;

    ContentType(String code, String contentHeader, ProgrammingLanguageEnum language) {
        this.code = code;
        this.contentHeader = contentHeader;
        this.language = language;
    }

    public static ContentType fromContentType(String contentType) {
        return Arrays
                .stream(VALUES)
                .filter(type -> type.code.equalsIgnoreCase(contentType))
                .findFirst()
                .orElseThrow(
                        () -> new ValidationException(ValidationError.create("Invalid content type {}", contentType)));
    }

    public String getContentType() {
        return code;
    }

    public String getContentHeader() {
        return contentHeader;
    }

    public ProgrammingLanguageEnum getLanguage() {
        return language;
    }
}
