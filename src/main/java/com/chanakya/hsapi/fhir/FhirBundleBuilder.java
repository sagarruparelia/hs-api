package com.chanakya.hsapi.fhir;

import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class FhirBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(FhirBundleBuilder.class);

    // Maps both our enum names and FHIR resource type names to the HealthLake resource type
    private static final Map<String, String> RESOURCE_TYPE_MAP = Map.ofEntries(
        // Our enum names (uppercase)
        Map.entry("MEDICATION", "MedicationRequest"),
        Map.entry("IMMUNIZATION", "Immunization"),
        Map.entry("ALLERGY", "AllergyIntolerance"),
        Map.entry("CONDITION", "Condition"),
        Map.entry("PROCEDURE", "Procedure"),
        Map.entry("LAB_RESULT", "Observation"),
        Map.entry("COVERAGE", "Coverage"),
        Map.entry("CLAIM", "ExplanationOfBenefit"),
        Map.entry("APPOINTMENT", "Appointment"),
        Map.entry("CARE_TEAM", "CareTeam"),
        // FHIR resource type names (as stored by existing data)
        Map.entry("MedicationRequest", "MedicationRequest"),
        Map.entry("AllergyIntolerance", "AllergyIntolerance"),
        Map.entry("ExplanationOfBenefit", "ExplanationOfBenefit"),
        Map.entry("Observation", "Observation"),
        Map.entry("CareTeam", "CareTeam")
    );

    private final FhirClient fhirClient;

    public FhirBundleBuilder(FhirClient fhirClient) {
        this.fhirClient = fhirClient;
    }

    public Bundle buildPatientSharedBundle(String healthLakePatientId,
                                           List<String> selectedResources,
                                           boolean includePdf,
                                           byte[] pdfBytes,
                                           String patientName) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        bundle.setTimestamp(new Date());

        // Patient (required, 1..1)
        Bundle patientBundle = fhirClient.getPatient(healthLakePatientId);
        Patient patient = null;
        if (patientBundle.hasEntry()) {
            for (Bundle.BundleEntryComponent entry : patientBundle.getEntry()) {
                if (entry.getResource() instanceof Patient p) {
                    patient = p;
                    bundle.addEntry().setResource(p);
                    break;
                }
            }
        }

        // DocumentReference (0..1, if includePdf=true)
        if (includePdf && pdfBytes != null && patient != null) {
            DocumentReference docRef = buildDocumentReference(pdfBytes, patient, patientName);
            bundle.addEntry().setResource(docRef);
        }

        // Discrete FHIR resources (parallel fetch)
        List<CompletableFuture<Bundle>> futures = selectedResources.stream()
            .filter(rt -> !"PATIENT".equalsIgnoreCase(rt) && !"Patient".equals(rt))
            .map(rt -> CompletableFuture.supplyAsync(() -> {
                String fhirType = RESOURCE_TYPE_MAP.getOrDefault(rt, rt);
                return fhirClient.searchResources(fhirType, healthLakePatientId);
            }))
            .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        for (var future : futures) {
            Bundle resourceBundle = future.join();
            if (resourceBundle != null && resourceBundle.hasEntry()) {
                for (Bundle.BundleEntryComponent entry : resourceBundle.getEntry()) {
                    bundle.addEntry().setResource(entry.getResource());
                }
            }
        }

        return bundle;
    }

    private DocumentReference buildDocumentReference(byte[] pdfBytes, Patient patient, String patientName) {
        DocumentReference docRef = new DocumentReference();
        docRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        docRef.setType(new CodeableConcept()
            .addCoding(new Coding()
                .setSystem("http://loinc.org")
                .setCode("60591-5")
                .setDisplay("Patient summary Document")));
        docRef.addCategory(new CodeableConcept()
            .addCoding(new Coding()
                .setSystem("http://hl7.org/fhir/us/core/CodeSystem/patient-shared-category")
                .setCode("patient-shared")
                .setDisplay("Patient Shared")));
        docRef.addAuthor(new Reference().setReference("Patient/" + patient.getIdElement().getIdPart()));
        docRef.addSecurityLabel(new CodeableConcept()
            .addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-Confidentiality")
                .setCode("PATAST")
                .setDisplay("patient asserted")));

        DocumentReference.DocumentReferenceContentComponent content = new DocumentReference.DocumentReferenceContentComponent();
        Attachment attachment = new Attachment();
        attachment.setContentType("application/pdf");
        attachment.setData(pdfBytes);
        content.setAttachment(attachment);
        docRef.addContent(content);

        return docRef;
    }
}
