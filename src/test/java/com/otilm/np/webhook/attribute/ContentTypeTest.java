package com.otilm.np.webhook.attribute;

import com.otilm.api.exception.ValidationException;
import com.otilm.api.model.common.attribute.common.content.data.ProgrammingLanguageEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentTypeTest {

    /**
     * The content type is persisted and echoed back through attribute content, so every declared value has to survive
     * the round trip through its wire name.
     */
    @ParameterizedTest
    @EnumSource(ContentType.class)
    void fromContentType_resolvesEveryDeclaredValue(ContentType contentType) {
        assertEquals(contentType, ContentType.fromContentType(contentType.getContentType()));
    }

    /** The value arrives from a path variable and from attribute content, so casing may differ. */
    @ParameterizedTest
    @EnumSource(ContentType.class)
    void fromContentType_ignoresCase(ContentType contentType) {
        assertEquals(contentType, ContentType.fromContentType(contentType.getContentType().toUpperCase()));
    }

    @Test
    void fromContentType_rejectsUnknownValue() {
        ValidationException ex = assertThrows(ValidationException.class, () -> ContentType.fromContentType("yaml"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void fromContentType_rejectsNull() {
        assertThrows(ValidationException.class, () -> ContentType.fromContentType(null));
    }

    @Test
    void contentHeaderMatchesTheRenderedFormat() {
        assertEquals("application/json", ContentType.RAW_JSON.getContentHeader());
        assertEquals("application/json", ContentType.JSON.getContentHeader());
        assertEquals("application/xml", ContentType.XML.getContentHeader());
    }

    @Test
    void languageDrivesTheTemplateEditorHighlighting() {
        assertEquals(ProgrammingLanguageEnum.JSON, ContentType.RAW_JSON.getLanguage());
        assertEquals(ProgrammingLanguageEnum.JSON, ContentType.JSON.getLanguage());
        assertEquals(ProgrammingLanguageEnum.XML, ContentType.XML.getLanguage());
    }
}
