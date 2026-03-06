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

    // Maps FHIR resource type names to HealthLake resource type strings.
    // Enum values now match FHIR names directly; this map handles the Observation special case
    // (selectedResources stores "Observation" but the search must use "Observation").
    private static final Map<String, String> RESOURCE_TYPE_MAP = Map.ofEntries(
        Map.entry("MedicationRequest", "MedicationRequest"),
        Map.entry("Immunization", "Immunization"),
        Map.entry("AllergyIntolerance", "AllergyIntolerance"),
        Map.entry("Condition", "Condition"),
        Map.entry("Procedure", "Procedure"),
        Map.entry("Observation", "Observation"),
        Map.entry("Coverage", "Coverage"),
        Map.entry("ExplanationOfBenefit", "ExplanationOfBenefit"),
        Map.entry("Appointment", "Appointment"),
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
                    stripMetaProfile(p);
                    addEntry(bundle, p);
                    break;
                }
            }
        }

        // DocumentReference (0..1, if includePdf=true)
        if (includePdf && pdfBytes != null && patient != null) {
            DocumentReference docRef = buildDocumentReference(pdfBytes, patient, patientName);
            addEntry(bundle, docRef);
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
                    Resource resource = entry.getResource();
                    if (resource == null) continue;
                    stripMetaProfile(resource);
                    addEntry(bundle, resource);
                }
            }
        }

        return bundle;
    }

    private DocumentReference buildDocumentReference(byte[] pdfBytes, Patient patient, String patientName) {
        DocumentReference docRef = new DocumentReference();
        docRef.setId(UUID.randomUUID().toString());
        docRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        docRef.setDate(new Date());
        docRef.setType(new CodeableConcept()
            .addCoding(new Coding()
                .setSystem("http://loinc.org")
                .setCode("60591-5")
                .setDisplay("Patient summary Document")));
        docRef.addCategory(new CodeableConcept()
            .addCoding(new Coding()
                .setSystem("https://cms.gov/fhir/CodeSystem/patient-shared-category")
                .setCode("patient-shared")
                .setDisplay("Patient Shared")));
        String patientRef = "Patient/" + patient.getIdElement().getIdPart();
        docRef.setSubject(new Reference().setReference(patientRef));
        docRef.addAuthor(new Reference().setReference(patientRef));
        docRef.addSecurityLabel(new CodeableConcept()
            .addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ObservationValue")
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

    private void addEntry(Bundle bundle, Resource resource) {
        String fullUrl = resource.getIdElement().hasIdPart()
            ? resource.fhirType() + "/" + resource.getIdElement().getIdPart()
            : "urn:uuid:" + UUID.randomUUID();
        bundle.addEntry()
            .setFullUrl(fullUrl)
            .setResource(resource);
    }

    private void stripMetaProfile(Resource resource) {
        if (resource == null) return;
        if (resource.hasMeta() && resource.getMeta().hasProfile()) {
            resource.getMeta().getProfile().clear();
        }
    }
}
