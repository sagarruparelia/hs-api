package com.chanakya.hsapi.graphql.type;

import java.time.LocalDate;
import java.util.List;

public record CareTeamType(
    String id,
    String name,
    String status,
    String category,
    List<String> participants,
    LocalDate startDate
) {}
