package com.chanakya.hsapi.shl.dto;

import java.time.Instant;

public record ShlCreateResponse(
    String linkId,
    String shlinkUrl,
    Instant expiresAt
) {}
