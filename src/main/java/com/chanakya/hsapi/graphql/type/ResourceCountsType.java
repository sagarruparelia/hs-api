package com.chanakya.hsapi.graphql.type;

public record ResourceCountsType(
    int medications,
    int immunizations,
    int allergies,
    int conditions,
    int procedures,
    int labResults,
    int coverages,
    int claims,
    int appointments,
    int careTeams,
    int total
) {}
