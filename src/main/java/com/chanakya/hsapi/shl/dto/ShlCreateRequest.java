package com.chanakya.hsapi.shl.dto;

import com.chanakya.hsapi.shl.model.FhirResourceType;

import java.util.List;

public record ShlCreateRequest(
    String idType,
    String idValue,
    String label,
    String expiresAt,
    List<FhirResourceType> selectedResources,
    boolean includePdf,
    String patientName,
    String mode
) {}
