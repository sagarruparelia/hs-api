package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record MedicationType(
    String id,
    String name,
    String status,
    String dosage,
    String reason,
    LocalDate startDate,
    LocalDate endDate
) {}
