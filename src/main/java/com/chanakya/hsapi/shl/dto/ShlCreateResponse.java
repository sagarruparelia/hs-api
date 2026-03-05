package com.chanakya.hsapi.shl.dto;

import java.time.Instant;

public record ShlCreateResponse(
    String hsid_uuid,
    String shlinkUrl,
    String qrData,
    Instant expiresAt
) {}
