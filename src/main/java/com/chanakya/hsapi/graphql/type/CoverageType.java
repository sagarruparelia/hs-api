package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record CoverageType(
    String id,
    String type,
    String status,
    String payor,
    String subscriberId,
    LocalDate startDate,
    LocalDate endDate
) {}
