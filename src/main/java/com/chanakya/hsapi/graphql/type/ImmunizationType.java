package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record ImmunizationType(
    String id,
    String name,
    String status,
    LocalDate date,
    String site,
    String performer
) {}
