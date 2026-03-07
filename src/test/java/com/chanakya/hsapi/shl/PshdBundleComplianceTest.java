package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.fhir.FhirBundleBuilder;
import com.chanakya.hsapi.fhir.FhirClient;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Validates FhirBundleBuilder output against the PSHD (Patient-Shared Health Documents)
 * specification from HackMD + HL7 FHIR IG.
 *
 * Spec: https://hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/rkGeS5cIZe
 */
class PshdBundleComplianceTest {

    private FhirClient fhirClient;
    private FhirBundleBuilder builder;

    @BeforeEach
    void setUp() {
        fhirClient = mock(FhirClient.class);
        builder = new FhirBundleBuilder(fhirClient);

        // Mock Patient lookup
        Patient patient = new Patient();
        patient.setId("test-patient-123");
        patient.addName().setFamily("Doe").addGiven("John");
        patient.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient");

        Bundle patientBundle = new Bundle();
        patientBundle.addEntry().setResource(patient);
        when(fhirClient.getPatient("HL-PAT-123")).thenReturn(patientBundle);

        // Mock resource searches
        Condition condition = new Condition();
        condition.setId("cond-1");
        condition.getMeta().addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition");
        Bundle conditionBundle = new Bundle();
        conditionBundle.addEntry().setResource(condition);
        when(fhirClient.searchResources("Condition", "HL-PAT-123")).thenReturn(conditionBundle);

        MedicationRequest med = new MedicationRequest();
        med.setId("med-1");
        Bundle medBundle = new Bundle();
        medBundle.addEntry().setResource(med);
        when(fhirClient.searchResources("MedicationRequest", "HL-PAT-123")).thenReturn(medBundle);
    }

    @Test
    void bundle_hasCollectionType() {
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        assertEquals(Bundle.BundleType.COLLECTION, bundle.getType());
    }

    @Test
    void bundle_hasTimestamp() {
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        assertNotNull(bundle.getTimestamp(), "Bundle.timestamp is required by PSHD spec");
    }

    @Test
    void bundle_containsPatient() {
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        boolean hasPatient = bundle.getEntry().stream()
            .anyMatch(e -> e.getResource() instanceof Patient);
        assertTrue(hasPatient, "Bundle must contain exactly 1 Patient resource");
    }

    @Test
    void bundle_patientHasNoMetaProfile() {
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        Patient patient = (Patient) bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Patient)
            .findFirst().orElseThrow().getResource();
        assertTrue(
            !patient.hasMeta() || !patient.getMeta().hasProfile(),
            "Patient resource SHOULD NOT include meta.profile per PSHD spec");
    }

    @Test
    void bundle_documentReference_hasCorrectType() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        assertNotNull(docRef, "Bundle must contain DocumentReference when PDF is attached");

        Coding typeCoding = docRef.getType().getCodingFirstRep();
        assertEquals("http://loinc.org", typeCoding.getSystem());
        assertEquals("60591-5", typeCoding.getCode());
        assertEquals("Patient summary Document", typeCoding.getDisplay());
    }

    @Test
    void bundle_documentReference_hasPatientSharedCategory() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        Coding categoryCoding = docRef.getCategoryFirstRep().getCodingFirstRep();
        assertEquals("https://cms.gov/fhir/CodeSystem/patient-shared-category", categoryCoding.getSystem());
        assertEquals("patient-shared", categoryCoding.getCode());
    }

    @Test
    void bundle_documentReference_statusIsCurrent() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        assertEquals(Enumerations.DocumentReferenceStatus.CURRENT, docRef.getStatus());
    }

    @Test
    void bundle_documentReference_authorReferencesPatient() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        assertTrue(docRef.hasAuthor(), "DocumentReference must have author");
        String authorRef = docRef.getAuthorFirstRep().getReference();
        assertTrue(authorRef.startsWith("Patient/"), "Author must reference Patient resource");
    }

    @Test
    void bundle_documentReference_subjectReferencesPatient() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        assertTrue(docRef.hasSubject(), "DocumentReference must have subject");
        String subjectRef = docRef.getSubject().getReference();
        assertTrue(subjectRef.startsWith("Patient/"), "Subject must reference Patient resource");
    }

    @Test
    void bundle_documentReference_hasPdfAttachment() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        assertTrue(docRef.hasContent(), "DocumentReference must have content");
        Attachment attachment = docRef.getContentFirstRep().getAttachment();
        assertEquals("application/pdf", attachment.getContentType());
        assertNotNull(attachment.getData(), "Attachment must contain PDF data");
        assertTrue(attachment.getData().length > 0, "PDF data must not be empty");
    }

    @Test
    void bundle_documentReference_hasPatastSecurityLabel() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        assertTrue(docRef.hasSecurityLabel(), "DocumentReference SHOULD have securityLabel");
        Coding securityCoding = docRef.getSecurityLabelFirstRep().getCodingFirstRep();
        assertEquals("http://terminology.hl7.org/CodeSystem/v3-ObservationValue", securityCoding.getSystem());
        assertEquals("PATAST", securityCoding.getCode());
    }

    @Test
    void bundle_documentReference_hasDate() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        DocumentReference docRef = findDocumentReference(bundle);
        assertNotNull(docRef.getDate(), "DocumentReference must have date");
    }

    @Test
    void bundle_discreteResources_noMetaProfile() {
        Bundle bundle = builder.buildPatientSharedBundle(
            "HL-PAT-123", List.of("Condition", "MedicationRequest"));

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            if (resource.hasMeta() && resource.getMeta().hasProfile()) {
                fail("Resource " + resource.fhirType() + "/" + resource.getIdElement().getIdPart()
                    + " has meta.profile — PSHD spec says SHOULD NOT include meta.profile");
            }
        }
    }

    @Test
    void bundle_withoutPdf_noDocumentReference() {
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));

        DocumentReference docRef = findDocumentReference(bundle);
        assertNull(docRef, "Bundle should not contain DocumentReference when no PDF attached");
    }

    @Test
    void bundle_allEntries_haveFullUrl() {
        byte[] pdfBytes = "fake-pdf-content".getBytes();
        Bundle bundle = builder.buildPatientSharedBundle("HL-PAT-123", List.of("Condition"));
        builder.addPdfDocumentReference(bundle, pdfBytes, "John Doe");

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            assertNotNull(entry.getFullUrl(),
                "Every Bundle entry must have fullUrl per FHIR spec");
            assertFalse(entry.getFullUrl().isBlank(),
                "fullUrl must not be blank");
        }
    }

    private DocumentReference findDocumentReference(Bundle bundle) {
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof DocumentReference)
            .map(e -> (DocumentReference) e.getResource())
            .findFirst()
            .orElse(null);
    }
}
