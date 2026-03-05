package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record AllergyType(
    String id,
    String substance,
    String category,
    String criticality,
    String status,
    LocalDate recordedDate,
    String reaction
) {}
