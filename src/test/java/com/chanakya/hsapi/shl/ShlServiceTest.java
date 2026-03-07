package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.shl.model.*;
import com.chanakya.hsapi.shl.service.ShlinkBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ShlServiceTest {

    @Test
    void effectiveStatus_active() {
        var link = new ShlLinkDocument();
        link.setStatus(ShlStatus.ACTIVE);
        link.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        assertEquals(ShlStatus.ACTIVE, link.getEffectiveStatus());
    }

    @Test
    void effectiveStatus_expired() {
        var link = new ShlLinkDocument();
        link.setStatus(ShlStatus.ACTIVE);
        link.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        assertEquals(ShlStatus.EXPIRED, link.getEffectiveStatus());
    }

    @Test
    void effectiveStatus_revoked() {
        var link = new ShlLinkDocument();
        link.setStatus(ShlStatus.REVOKED);
        link.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        assertEquals(ShlStatus.REVOKED, link.getEffectiveStatus());
    }

    @Test
    void shlinkBuilder_producesValidUri() {
        var builder = new ShlinkBuilder("https://api.example.com", JsonMapper.builder().build());
        String uri = builder.buildShlinkUri(
            "test-link-id", "U", "rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q",
            1706745600L, "Test Label");

        assertTrue(uri.startsWith("shlink:/"));
        String payload = uri.substring("shlink:/".length());
        String decoded = new String(Base64.getUrlDecoder().decode(payload));
        assertTrue(decoded.contains("\"url\":\"https://api.example.com/shl/test-link-id\""));
        assertTrue(decoded.contains("\"flag\":\"U\""));
        assertTrue(decoded.contains("\"key\":\"rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q\""));
        assertTrue(decoded.contains("\"exp\":1706745600"));
        assertTrue(decoded.contains("\"label\":\"Test Label\""));
    }

    @Test
    void shlFlag_validation() {
        assertTrue(ShlFlag.isValid("U"));
        assertTrue(ShlFlag.isValid("L"));
        assertFalse(ShlFlag.isValid("X"));
        assertFalse(ShlFlag.isValid(null));
        assertFalse(ShlFlag.isValid(""));

        assertTrue(ShlFlag.U.isSnapshot());
        assertFalse(ShlFlag.L.isSnapshot());
        assertTrue(ShlFlag.L.isLive());
        assertFalse(ShlFlag.U.isLive());
    }

    @Test
    void accessRecord_factory() {
        var record = AccessRecord.of("Dr. Smith", "ACCESSED");
        assertEquals("Dr. Smith", record.recipient());
        assertEquals("ACCESSED", record.action());
        assertNotNull(record.timestamp());
    }
}
