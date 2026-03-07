package com.chanakya.hsapi.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class PdfGenerationService {

    private final TemplateEngine templateEngine;

    public byte[] generatePatientSummaryPdf(String patientName, Map<String, Object> data) {
        Context context = new Context();
        context.setVariable("patientName", patientName);
        context.setVariables(data);

        String html = templateEngine.process("pdf/patient-summary", context);

        try (var os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            log.debug("Generated PDF for patient: {}", patientName);
            return os.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("PDF generation failed", e);
        }
    }
}
