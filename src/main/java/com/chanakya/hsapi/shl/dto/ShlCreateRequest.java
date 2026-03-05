package com.chanakya.hsapi.shl.dto;

import java.util.List;

public record ShlCreateRequest(
    String idType,
    String idValue,
    String label,
    String expiresAt,
    List<String> selectedResources,
    boolean includePdf,
    String patientName,
    String mode
) {}
