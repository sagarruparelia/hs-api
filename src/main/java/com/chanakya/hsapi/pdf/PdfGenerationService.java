package com.chanakya.hsapi.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@RequiredArgsConstructor
@Service
public class PdfGenerationService {

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a");

    private final TemplateEngine templateEngine;
    private final PdfDataExtractor pdfDataExtractor;

    public byte[] generatePatientSummaryPdf(Bundle bundle, String patientName) {
        var pdfData = pdfDataExtractor.extract(bundle, patientName);
        String generatedDate = LocalDateTime.now().format(DISPLAY_FMT);

        Context context = new Context();
        context.setVariable("patientInfo", pdfData.patientInfo());
        context.setVariable("sections", pdfData.sections());
        context.setVariable("patientName", patientName);
        context.setVariable("generatedDate", generatedDate);

        String html = templateEngine.process("pdf/patient-summary", context);

        try (var os = new ByteArrayOutputStream()) {
            var builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();

            byte[] pdfBytes = addMetadata(os.toByteArray(), patientName);
            log.debug("Generated PDF for patient: {} ({} bytes)", patientName, pdfBytes.length);
            return pdfBytes;
        } catch (Exception e) {
            throw new RuntimeException("PDF generation failed", e);
        }
    }

    private byte[] addMetadata(byte[] pdfBytes, String patientName) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Health Records - " + patientName);
            info.setAuthor("HealthSafe");
            info.setSubject("Smart Health Link - Shared Health Records");
            doc.setDocumentInformation(info);

            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to add PDF metadata, returning PDF without metadata", e);
            return pdfBytes;
        }
    }
}
