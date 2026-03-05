package com.chanakya.hsapi.fhir;

import com.chanakya.hsapi.shl.model.FhirResourceType;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class FhirBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(FhirBundleBuilder.class);

    private final FhirClient fhirClient;

    public FhirBundleBuilder(FhirClient fhirClient) {
        this.fhirClient = fhirClient;
    }

    public Bundle buildPatientSharedBundle(String healthLakePatientId,
                                           List<FhirResourceType> selectedResources,
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
        Map<FhirResourceType, String> resourceTypeMapping = Map.of(
            FhirResourceType.MEDICATION, "MedicationRequest",
            FhirResourceType.IMMUNIZATION, "Immunization",
            FhirResourceType.ALLERGY, "AllergyIntolerance",
            FhirResourceType.CONDITION, "Condition",
            FhirResourceType.PROCEDURE, "Procedure",
            FhirResourceType.LAB_RESULT, "Observation",
            FhirResourceType.COVERAGE, "Coverage",
            FhirResourceType.CLAIM, "ExplanationOfBenefit",
            FhirResourceType.APPOINTMENT, "Appointment",
            FhirResourceType.CARE_TEAM, "CareTeam"
        );

        List<CompletableFuture<Bundle>> futures = selectedResources.stream()
            .filter(rt -> rt != FhirResourceType.PATIENT)
            .map(rt -> CompletableFuture.supplyAsync(() -> {
                String fhirType = resourceTypeMapping.getOrDefault(rt, rt.name());
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
