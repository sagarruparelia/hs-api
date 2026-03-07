package com.chanakya.hsapi.graphql;

import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.fhir.FhirClient;
import com.chanakya.hsapi.graphql.transform.PatientTransform;
import com.chanakya.hsapi.graphql.type.HealthDashboardType;
import com.chanakya.hsapi.graphql.type.PatientSummaryType;
import com.chanakya.hsapi.graphql.type.ResourceCountsType;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
@Controller
public class ResourceCountsController {

    private final PatientCrosswalkService crosswalk;
    private final FhirClient fhirClient;
    private final PatientTransform patientTransform;

    @QueryMapping
    public ResourceCountsType resourceCounts(@Argument String enterpriseId) {
        String patientId = crosswalk.resolveHealthLakePatientId(enterpriseId);
        return fetchCounts(patientId);
    }

    @QueryMapping
    public HealthDashboardType healthDashboard(@Argument String enterpriseId) {
        String patientId = crosswalk.resolveHealthLakePatientId(enterpriseId);

        var countsFuture = CompletableFuture.supplyAsync(() -> fetchCounts(patientId));
        var patientFuture = CompletableFuture.supplyAsync(() -> {
            var bundle = fhirClient.getPatient(patientId);
            return patientTransform.transform(bundle);
        });

        CompletableFuture.allOf(countsFuture, patientFuture).join();
        return new HealthDashboardType(countsFuture.join(), patientFuture.join());
    }

    private ResourceCountsType fetchCounts(String patientId) {
        var medications = CompletableFuture.supplyAsync(() -> fhirClient.countResources("MedicationRequest", patientId));
        var immunizations = CompletableFuture.supplyAsync(() -> fhirClient.countResources("Immunization", patientId));
        var allergies = CompletableFuture.supplyAsync(() -> fhirClient.countResources("AllergyIntolerance", patientId));
        var conditions = CompletableFuture.supplyAsync(() -> fhirClient.countResources("Condition", patientId));
        var procedures = CompletableFuture.supplyAsync(() -> fhirClient.countResources("Procedure", patientId));
        var labResults = CompletableFuture.supplyAsync(() -> fhirClient.countResources("Observation", patientId));
        var coverages = CompletableFuture.supplyAsync(() -> fhirClient.countResources("Coverage", patientId));
        var claims = CompletableFuture.supplyAsync(() -> fhirClient.countResources("ExplanationOfBenefit", patientId));
        var appointments = CompletableFuture.supplyAsync(() -> fhirClient.countResources("Appointment", patientId));
        var careTeams = CompletableFuture.supplyAsync(() -> fhirClient.countResources("CareTeam", patientId));

        CompletableFuture.allOf(medications, immunizations, allergies, conditions, procedures,
            labResults, coverages, claims, appointments, careTeams).join();

        int medCount = medications.join(), immCount = immunizations.join(),
            algCount = allergies.join(), condCount = conditions.join(),
            procCount = procedures.join(), labCount = labResults.join(),
            covCount = coverages.join(), claimCount = claims.join(),
            apptCount = appointments.join(), ctCount = careTeams.join();

        return new ResourceCountsType(medCount, immCount, algCount, condCount, procCount,
            labCount, covCount, claimCount, apptCount, ctCount,
            medCount + immCount + algCount + condCount + procCount +
                labCount + covCount + claimCount + apptCount + ctCount);
    }
}
