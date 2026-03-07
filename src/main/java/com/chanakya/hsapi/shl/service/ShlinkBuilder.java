package com.chanakya.hsapi.shl.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;

@RequiredArgsConstructor
@Service
public class ShlinkBuilder {

    @Value("${app.base-url}")
    private final String baseUrl;

    private final JsonMapper jsonMapper;

    public String buildShlinkUri(String linkId, String flag, String rawKeyBase64Url,
                                  long expiresAtEpochSeconds, String label) {
        String url = baseUrl + "/shl/" + linkId;

        var payload = new LinkedHashMap<String, Object>();
        payload.put("url", url);
        payload.put("flag", flag);
        payload.put("key", rawKeyBase64Url);
        payload.put("exp", expiresAtEpochSeconds);
        if (label != null && !label.isEmpty()) {
            payload.put("label", label);
        }

        try {
            String json = jsonMapper.writeValueAsString(payload);
            String base64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return "shlink:/" + base64Payload;
        } catch (tools.jackson.core.JacksonException e) {
            throw new RuntimeException("Failed to build SHLink payload", e);
        }
    }

    public String buildViewerUrl(String shlinkUri) {
        return shlinkUri;
    }
}
