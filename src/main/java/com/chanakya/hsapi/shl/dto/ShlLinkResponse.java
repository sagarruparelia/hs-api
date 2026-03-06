package com.chanakya.hsapi.shl.dto;

import com.chanakya.hsapi.shl.model.AccessRecord;

import java.time.Instant;
import java.util.List;

public record ShlLinkResponse(
    String linkId,
    String label,
    String mode,
    String flag,
    String effectiveStatus,
    String shlinkUrl,
    Instant expiresAt,
    Instant createdAt,
    List<String> selectedResources,
    boolean includePdf,
    List<AccessRecord> accessHistory
) {}
