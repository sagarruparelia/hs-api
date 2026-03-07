package com.chanakya.hsapi.pdf;

import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class PdfGenerationServiceTest {

    private PdfGenerationService pdfGenerationService;

    @BeforeEach
    void setUp() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        var templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        pdfGenerationService = new PdfGenerationService(templateEngine, new PdfDataExtractor());
    }

    @Test
    void generatesValidPdfWithClinicalData() {
        Bundle bundle = buildTestBundle();

        byte[] pdf = pdfGenerationService.generatePatientSummaryPdf(bundle, "John Doe");

        assertThat(pdf).isNotEmpty();
        assertThat(pdf[0]).isEqualTo((byte) '%');
        assertThat(pdf[1]).isEqualTo((byte) 'P');
        assertThat(pdf[2]).isEqualTo((byte) 'D');
        assertThat(pdf[3]).isEqualTo((byte) 'F');
    }

    @Test
    void generatesValidPdfWithEmptyBundle() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);

        byte[] pdf = pdfGenerationService.generatePatientSummaryPdf(bundle, "Empty Patient");

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void pdfContainsMetadata() throws Exception {
        Bundle bundle = buildTestBundle();

        byte[] pdf = pdfGenerationService.generatePatientSummaryPdf(bundle, "Jane Smith");

        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            var info = doc.getDocumentInformation();
            assertThat(info.getTitle()).isEqualTo("Health Records - Jane Smith");
            assertThat(info.getAuthor()).isEqualTo("HealthSafe");
            assertThat(info.getSubject()).isEqualTo("Smart Health Link - Shared Health Records");
        }
    }

    private Bundle buildTestBundle() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);

        Patient patient = new Patient();
        patient.setId("p1");
        patient.addName().setFamily("Doe").addGiven("John");
        patient.setBirthDate(new Date(90, 0, 15));
        patient.setGender(Enumerations.AdministrativeGender.MALE);
        addEntry(bundle, patient);

        MedicationRequest mr = new MedicationRequest();
        mr.setId("med1");
        mr.setMedication(new CodeableConcept().setText("Lisinopril 10mg"));
        mr.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        mr.addDosageInstruction().setText("Take once daily");
        addEntry(bundle, mr);

        Condition cond = new Condition();
        cond.setId("cond1");
        cond.setCode(new CodeableConcept().setText("Hypertension"));
        addEntry(bundle, cond);

        AllergyIntolerance ai = new AllergyIntolerance();
        ai.setId("ai1");
        ai.setCode(new CodeableConcept().setText("Penicillin"));
        ai.addReaction().addManifestation(new CodeableConcept().setText("Hives"));
        addEntry(bundle, ai);

        return bundle;
    }

    private void addEntry(Bundle bundle, Resource resource) {
        bundle.addEntry()
            .setFullUrl(resource.fhirType() + "/" + resource.getIdElement().getIdPart())
            .setResource(resource);
    }
}
