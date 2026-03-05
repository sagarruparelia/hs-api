package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record ConditionType(
    String id,
    String name,
    String status,
    String category,
    LocalDate onsetDate,
    LocalDate abatementDate
) {}
