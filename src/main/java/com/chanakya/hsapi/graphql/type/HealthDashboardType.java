package com.chanakya.hsapi.graphql.type;

public record HealthDashboardType(
    ResourceCountsType resourceCounts,
    PatientSummaryType patientSummary
) {}
