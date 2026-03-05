package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;

public record ProcedureType(
    String id,
    String name,
    String status,
    LocalDate performedDate,
    String performer,
    String location
) {}
