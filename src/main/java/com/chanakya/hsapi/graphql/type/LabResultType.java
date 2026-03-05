package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record LabResultType(
    String id,
    String name,
    String value,
    String unit,
    String status,
    LocalDate effectiveDate,
    String referenceRange,
    String interpretation
) {}
