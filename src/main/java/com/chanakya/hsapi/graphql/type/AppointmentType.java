package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record AppointmentType(
    String id,
    String status,
    String type,
    String description,
    LocalDate date,
    String participant,
    String location
) {}
