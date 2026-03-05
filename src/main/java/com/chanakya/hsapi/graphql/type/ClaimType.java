package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record ClaimType(
    String id,
    String type,
    String status,
    String provider,
    LocalDate serviceDate,
    String totalAmount,
    String currency
) {}
