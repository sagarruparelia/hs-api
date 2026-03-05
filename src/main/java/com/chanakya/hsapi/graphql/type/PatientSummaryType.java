package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record PatientSummaryType(
    String id,
    String firstName,
    String lastName,
    LocalDate birthDate,
    String gender,
    String address,
    String phone,
    String email
) {}
