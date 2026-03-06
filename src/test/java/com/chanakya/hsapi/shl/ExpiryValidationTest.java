package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.crypto.EncryptionService;
import com.chanakya.hsapi.crypto.FieldEncryptionService;
import com.chanakya.hsapi.crypto.KeyGenerationService;
import com.chanakya.hsapi.fhir.FhirBundleBuilder;
import com.chanakya.hsapi.fhir.FhirSerializationService;
import com.chanakya.hsapi.pdf.PdfGenerationService;
import com.chanakya.hsapi.shl.dto.ShlCreateRequest;
import com.chanakya.hsapi.shl.repository.ShlLinkRepository;
import com.chanakya.hsapi.shl.service.ShlService;
import com.chanakya.hsapi.shl.service.ShlinkBuilder;
import com.chanakya.hsapi.storage.S3PayloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Validates expiresAt boundary enforcement per architecture spec.
 * Spec: expiresAt REQUIRED, ISO-8601, min 5 minutes, max 365 days from now.
 */
class ExpiryValidationTest {

    private ShlService shlService;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        var linkRepo = mock(ShlLinkRepository.class);
        var keyGen = mock(KeyGenerationService.class);
        var encryption = mock(EncryptionService.class);
        var fieldEncryption = mock(FieldEncryptionService.class);
        var bundleBuilder = mock(FhirBundleBuilder.class);
        var fhirSerialization = mock(FhirSerializationService.class);
        var s3 = mock(S3PayloadService.class);
        var crosswalk = mock(PatientCrosswalkService.class);
        var shlinkBuilder = mock(ShlinkBuilder.class);
        var auditService = mock(AuditService.class);
        var mongoTemplate = mock(MongoTemplate.class);
        var pdfGeneration = mock(PdfGenerationService.class);

        shlService = new ShlService(linkRepo, keyGen, encryption, fieldEncryption,
            bundleBuilder, fhirSerialization, s3, crosswalk, shlinkBuilder,
            auditService, mongoTemplate, pdfGeneration);

        httpRequest = new MockHttpServletRequest();
    }

    @Test
    void create_rejectsNullExpiresAt() {
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", null,
            List.of("Condition"), false, null, "snapshot");
        var ex = assertThrows(IllegalArgumentException.class, () -> shlService.create(req, httpRequest));
        assertTrue(ex.getMessage().contains("expiresAt is required"));
    }

    @Test
    void create_rejectsBlankExpiresAt() {
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", "",
            List.of("Condition"), false, null, "snapshot");
        var ex = assertThrows(IllegalArgumentException.class, () -> shlService.create(req, httpRequest));
        assertTrue(ex.getMessage().contains("expiresAt is required"));
    }

    @Test
    void create_rejectsInvalidIso8601() {
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", "not-a-date",
            List.of("Condition"), false, null, "snapshot");
        var ex = assertThrows(IllegalArgumentException.class, () -> shlService.create(req, httpRequest));
        assertTrue(ex.getMessage().contains("ISO-8601"));
    }

    @Test
    void create_rejectsExpiryLessThan5Minutes() {
        String tooSoon = Instant.now().plus(2, ChronoUnit.MINUTES).toString();
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", tooSoon,
            List.of("Condition"), false, null, "snapshot");
        var ex = assertThrows(IllegalArgumentException.class, () -> shlService.create(req, httpRequest));
        assertTrue(ex.getMessage().contains("at least 5 minutes"));
    }

    @Test
    void create_rejectsExpiryMoreThan365Days() {
        String tooLate = Instant.now().plus(366, ChronoUnit.DAYS).toString();
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", tooLate,
            List.of("Condition"), false, null, "snapshot");
        var ex = assertThrows(IllegalArgumentException.class, () -> shlService.create(req, httpRequest));
        assertTrue(ex.getMessage().contains("at most 365 days"));
    }

    @Test
    void create_acceptsExpiryAt6Minutes() {
        // 6 minutes from now should be valid (safely past the 5-minute minimum)
        String validExpiry = Instant.now().plus(6, ChronoUnit.MINUTES).toString();
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", validExpiry,
            List.of("Condition"), false, null, "live");

        // Should not throw — mock dependencies will return null/defaults
        // We only need to verify no IllegalArgumentException for the expiry
        // The actual flow will fail on keyGen.generateLinkId() returning null — that's OK
        try {
            shlService.create(req, httpRequest);
        } catch (IllegalArgumentException e) {
            fail("Valid expiresAt (6 min) should not throw IllegalArgumentException: " + e.getMessage());
        } catch (Exception e) {
            // Other exceptions from mocked dependencies are expected
        }
    }

    @Test
    void create_acceptsExpiryAt364Days() {
        // 364 days from now should be valid (safely within 365-day maximum)
        String validExpiry = Instant.now().plus(364, ChronoUnit.DAYS).toString();
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", validExpiry,
            List.of("Condition"), false, null, "live");

        try {
            shlService.create(req, httpRequest);
        } catch (IllegalArgumentException e) {
            fail("Valid expiresAt (364 days) should not throw IllegalArgumentException: " + e.getMessage());
        } catch (Exception e) {
            // Other exceptions from mocked dependencies are expected
        }
    }

    @Test
    void create_rejectsMissingPatientNameWhenPdfIncluded() {
        String validExpiry = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        var req = new ShlCreateRequest("EID", "ENT-001", "Test", validExpiry,
            List.of("Condition"), true, null, "snapshot");
        var ex = assertThrows(IllegalArgumentException.class, () -> shlService.create(req, httpRequest));
        assertTrue(ex.getMessage().contains("patientName is required"));
    }
}
