package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.crypto.KeyGenerationService;
import com.chanakya.hsapi.shl.service.ShlinkBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates SHLink URI format against SMART Health Links specification.
 *
 * Format: shlink:/<base64url-encoded-JSON>
 * JSON payload: {url, flag, key, exp, label?}
 * Reference: https://hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/rkGeS5cIZe
 */
class ShlinkUriComplianceTest {

    private final ShlinkBuilder builder = new ShlinkBuilder("https://api.example.com", JsonMapper.builder().build());
    private final KeyGenerationService keyGen = new KeyGenerationService();

    private static final String LINK_ID = "test-link-abc123";
    private static final String KEY_43_CHARS = "rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q";
    private static final long EXP = 1706745600L;

    @Test
    void shlinkUri_startsWithCorrectScheme() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        assertTrue(uri.startsWith("shlink:/"),
            "SHLink URI must start with shlink:/ scheme");
    }

    @Test
    void shlinkUri_payloadIsBase64UrlEncoded() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        String payload = uri.substring("shlink:/".length());
        assertDoesNotThrow(() -> Base64.getUrlDecoder().decode(payload),
            "Payload after shlink:/ must be valid base64url");
    }

    @Test
    void shlinkUri_containsUrl() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        String json = decodePayload(uri);
        assertTrue(json.contains("\"url\":\"https://api.example.com/shl/" + LINK_ID + "\""),
            "Payload must contain url field pointing to /shl/{linkId}");
    }

    @Test
    void shlinkUri_containsFlag() {
        String uriU = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        assertTrue(decodePayload(uriU).contains("\"flag\":\"U\""),
            "Snapshot URI must have flag=U");

        String uriL = builder.buildShlinkUri(LINK_ID, "L", KEY_43_CHARS, EXP, null);
        assertTrue(decodePayload(uriL).contains("\"flag\":\"L\""),
            "Live URI must have flag=L");
    }

    @Test
    void shlinkUri_containsKey() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        String json = decodePayload(uri);
        assertTrue(json.contains("\"key\":\"" + KEY_43_CHARS + "\""),
            "Payload must contain the raw AES key (base64url-encoded)");
    }

    @Test
    void shlinkUri_containsExp() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        String json = decodePayload(uri);
        assertTrue(json.contains("\"exp\":" + EXP),
            "Payload must contain exp as integer (Unix timestamp)");
    }

    @Test
    void shlinkUri_labelIncludedWhenProvided() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, "My Health Summary");
        String json = decodePayload(uri);
        assertTrue(json.contains("\"label\":\"My Health Summary\""),
            "Label must be included when provided");
    }

    @Test
    void shlinkUri_labelAbsentWhenNull() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        String json = decodePayload(uri);
        assertFalse(json.contains("\"label\""),
            "Label must be absent when null");
    }

    @Test
    void shlinkUri_labelAbsentWhenEmpty() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, "");
        String json = decodePayload(uri);
        assertFalse(json.contains("\"label\""),
            "Label must be absent when empty string");
    }

    @Test
    void shlinkUri_urlContainsLinkId() {
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, null);
        String json = decodePayload(uri);
        assertTrue(json.contains("/shl/" + LINK_ID),
            "URL in payload must end with /shl/{linkId}");
    }

    @Test
    void shlinkUri_keyIs43Chars() {
        String rawKey = keyGen.generateAesKeyBase64Url();
        assertEquals(43, rawKey.length(),
            "Base64url encoding of 32-byte key must be exactly 43 chars (no padding)");

        String uri = builder.buildShlinkUri(LINK_ID, "U", rawKey, EXP, null);
        String json = decodePayload(uri);
        assertTrue(json.contains("\"key\":\"" + rawKey + "\""),
            "Generated key must appear in shlink payload");
    }

    @Test
    void shlinkUri_specialCharsInLabel_escaped() {
        String labelWithQuotes = "Patient's \"health\" summary\nnew line";
        String uri = builder.buildShlinkUri(LINK_ID, "U", KEY_43_CHARS, EXP, labelWithQuotes);
        String json = decodePayload(uri);
        // The label should be JSON-escaped (no raw quotes or newlines)
        assertFalse(json.contains("\n"), "Newlines must be escaped in JSON");
        assertTrue(json.contains("\\\"health\\\""), "Quotes must be escaped in JSON");
    }

    private String decodePayload(String uri) {
        String payload = uri.substring("shlink:/".length());
        return new String(Base64.getUrlDecoder().decode(payload));
    }
}
