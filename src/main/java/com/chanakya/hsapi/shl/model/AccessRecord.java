package com.chanakya.hsapi.shl.model;

import java.time.Instant;

public record AccessRecord(
    String recipient,
    String action,
    Instant timestamp
) {
    public static AccessRecord of(String recipient, String action) {
        return new AccessRecord(recipient, action, Instant.now());
    }
}
