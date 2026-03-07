package com.chanakya.hsapi.pdf;

import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDataExtractorTest {

    private PdfDataExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PdfDataExtractor();
    }

    @Test
    void extractsPatientInfoFromBundle() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);

        Patient patient = new Patient();
        patient.setId("p1");
        patient.addName().setFamily("Doe").addGiven("John");
        patient.setBirthDate(new Date(90, 0, 15)); // 1990-01-15
        patient.setGender(Enumerations.AdministrativeGender.MALE);
        addEntry(bundle, patient);

        var result = extractor.extract(bundle, "Fallback Name");

        assertThat(result.patientInfo().name()).isEqualTo("John Doe");
        assertThat(result.patientInfo().birthDate()).isNotNull();
        assertThat(result.patientInfo().gender()).isEqualTo("Male");
    }

    @Test
    void extractsMedicationSection() {
        Bundle bundle = buildBundleWithPatient();

        MedicationRequest mr = new MedicationRequest();
        mr.setId("med1");
        mr.setMedication(new CodeableConcept().setText("Lisinopril 10mg"));
        mr.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        mr.addDosageInstruction().setText("Take once daily");
        addEntry(bundle, mr);

        var result = extractor.extract(bundle, "Test");

        assertThat(result.sections()).hasSize(1);
        var section = result.sections().getFirst();
        assertThat(section.title()).isEqualTo("Medications");
        assertThat(section.count()).isEqualTo(1);
        assertThat(section.lines().getFirst().summary()).contains("Lisinopril 10mg");
        assertThat(section.lines().getFirst().summary()).contains("active");
        assertThat(section.lines().getFirst().summary()).contains("Take once daily");
    }

    @Test
    void extractsConditionSection() {
        Bundle bundle = buildBundleWithPatient();

        Condition condition = new Condition();
        condition.setId("cond1");
        condition.setCode(new CodeableConcept().setText("Hypertension"));
        condition.setOnset(new DateTimeType(new Date(120, 5, 1))); // 2020-06-01
        addEntry(bundle, condition);

        var result = extractor.extract(bundle, "Test");

        assertThat(result.sections()).hasSize(1);
        var line = result.sections().getFirst().lines().getFirst().summary();
        assertThat(line).contains("Hypertension");
        assertThat(line).contains("onset:");
    }

    @Test
    void extractsAllergySection() {
        Bundle bundle = buildBundleWithPatient();

        AllergyIntolerance allergy = new AllergyIntolerance();
        allergy.setId("allergy1");
        allergy.setCode(new CodeableConcept().setText("Penicillin"));
        allergy.addReaction()
            .addManifestation(new CodeableConcept().setText("Rash"))
            .setSeverity(AllergyIntolerance.AllergyIntoleranceSeverity.MODERATE);
        addEntry(bundle, allergy);

        var result = extractor.extract(bundle, "Test");

        var line = result.sections().getFirst().lines().getFirst().summary();
        assertThat(line).contains("Penicillin");
        assertThat(line).contains("Rash");
        assertThat(line).contains("moderate");
    }

    @Test
    void extractsImmunizationSection() {
        Bundle bundle = buildBundleWithPatient();

        Immunization imm = new Immunization();
        imm.setId("imm1");
        imm.setVaccineCode(new CodeableConcept().setText("COVID-19 Vaccine"));
        imm.setOccurrence(new DateTimeType(new Date(121, 2, 15))); // 2021-03-15
        addEntry(bundle, imm);

        var result = extractor.extract(bundle, "Test");

        var line = result.sections().getFirst().lines().getFirst().summary();
        assertThat(line).contains("COVID-19 Vaccine");
    }

    @Test
    void extractsObservationSection() {
        Bundle bundle = buildBundleWithPatient();

        Observation obs = new Observation();
        obs.setId("obs1");
        obs.setCode(new CodeableConcept().setText("Blood Pressure"));
        obs.setValue(new Quantity().setValue(120).setUnit("mmHg"));
        obs.setEffective(new DateTimeType(new Date(125, 0, 10)));
        addEntry(bundle, obs);

        var result = extractor.extract(bundle, "Test");

        var line = result.sections().getFirst().lines().getFirst().summary();
        assertThat(line).contains("Blood Pressure");
        assertThat(line).contains("120");
        assertThat(line).contains("mmHg");
    }

    @Test
    void extractsCoverageSection() {
        Bundle bundle = buildBundleWithPatient();

        Coverage coverage = new Coverage();
        coverage.setId("cov1");
        coverage.setType(new CodeableConcept().setText("Medicare Part A"));
        coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
        addEntry(bundle, coverage);

        var result = extractor.extract(bundle, "Test");

        var line = result.sections().getFirst().lines().getFirst().summary();
        assertThat(line).contains("Medicare Part A");
        assertThat(line).contains("active");
    }

    @Test
    void extractsCareTeamSection() {
        Bundle bundle = buildBundleWithPatient();

        CareTeam ct = new CareTeam();
        ct.setId("ct1");
        ct.setName("Primary Care Team");
        ct.addParticipant().setMember(new Reference().setDisplay("Dr. Smith"));
        ct.addParticipant().setMember(new Reference().setDisplay("Nurse Jones"));
        addEntry(bundle, ct);

        var result = extractor.extract(bundle, "Test");

        var line = result.sections().getFirst().lines().getFirst().summary();
        assertThat(line).contains("Primary Care Team");
        assertThat(line).contains("2 members");
    }

    @Test
    void handlesMultipleResourceTypes() {
        Bundle bundle = buildBundleWithPatient();

        MedicationRequest mr = new MedicationRequest();
        mr.setId("med1");
        mr.setMedication(new CodeableConcept().setText("Aspirin"));
        addEntry(bundle, mr);

        Condition cond = new Condition();
        cond.setId("cond1");
        cond.setCode(new CodeableConcept().setText("Diabetes"));
        addEntry(bundle, cond);

        AllergyIntolerance ai = new AllergyIntolerance();
        ai.setId("ai1");
        ai.setCode(new CodeableConcept().setText("Peanuts"));
        addEntry(bundle, ai);

        var result = extractor.extract(bundle, "Test");

        assertThat(result.sections()).hasSize(3);
        assertThat(result.sections().stream().map(PdfDataExtractor.ResourceSection::title))
            .containsExactly("Medications", "Conditions & Diagnoses", "Allergies");
    }

    @Test
    void skipsDocumentReferenceResources() {
        Bundle bundle = buildBundleWithPatient();

        DocumentReference docRef = new DocumentReference();
        docRef.setId("doc1");
        addEntry(bundle, docRef);

        var result = extractor.extract(bundle, "Test");

        assertThat(result.sections()).isEmpty();
    }

    @Test
    void fallsBackToPatientNameWhenNoPatientResource() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);

        var result = extractor.extract(bundle, "Fallback Name");

        assertThat(result.patientInfo().name()).isEqualTo("Fallback Name");
        assertThat(result.patientInfo().birthDate()).isNull();
        assertThat(result.patientInfo().gender()).isNull();
    }

    @Test
    void handlesNullBundle() {
        var result = extractor.extract(null, "Test");

        assertThat(result.patientInfo().name()).isEqualTo("Test");
        assertThat(result.sections()).isEmpty();
    }

    private Bundle buildBundleWithPatient() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        Patient patient = new Patient();
        patient.setId("p1");
        patient.addName().setFamily("Test").addGiven("Patient");
        addEntry(bundle, patient);
        return bundle;
    }

    private void addEntry(Bundle bundle, Resource resource) {
        bundle.addEntry()
            .setFullUrl(resource.fhirType() + "/" + resource.getIdElement().getIdPart())
            .setResource(resource);
    }
}
