package com.chanakya.hsapi.shl.dto;

import java.time.Instant;
import java.util.List;

public record ShlLinkResponse(
    String hsid_uuid,
    String label,
    String mode,
    String flag,
    String effectiveStatus,
    String shlinkUrl,
    String qrData,
    Instant expiresAt,
    Instant createdAt,
    List<String> selectedResources,
    boolean includePdf
) {}
