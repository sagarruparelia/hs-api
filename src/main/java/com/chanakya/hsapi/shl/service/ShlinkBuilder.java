package com.chanakya.hsapi.shl.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class ShlinkBuilder {

    private final String baseUrl;

    public ShlinkBuilder(@Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String buildShlinkUri(String linkId, String flag, String rawKeyBase64Url,
                                  long expiresAtEpochSeconds, String label) {
        // Build the SHLink payload JSON
        String url = baseUrl + "/shl/" + linkId;
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"url\":\"").append(url).append("\"");
        json.append(",\"flag\":\"").append(flag).append("\"");
        json.append(",\"key\":\"").append(rawKeyBase64Url).append("\"");
        json.append(",\"exp\":").append(expiresAtEpochSeconds);
        if (label != null && !label.isEmpty()) {
            json.append(",\"label\":\"").append(escapeJson(label)).append("\"");
        }
        json.append("}");

        String base64Payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));

        return "shlink:/" + base64Payload;
    }

    public String buildViewerUrl(String shlinkUri) {
        // QR data is the shlink URI — recipients scan to get the viewer URL
        return shlinkUri;
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
