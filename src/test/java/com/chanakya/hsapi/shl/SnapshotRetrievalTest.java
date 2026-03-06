package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.crypto.EncryptionService;
import com.chanakya.hsapi.crypto.FieldEncryptionService;
import com.chanakya.hsapi.fhir.FhirBundleBuilder;
import com.chanakya.hsapi.fhir.FhirSerializationService;
import com.chanakya.hsapi.pdf.PdfGenerationService;
import com.chanakya.hsapi.shl.model.*;
import com.chanakya.hsapi.shl.repository.ShlLinkRepository;
import com.chanakya.hsapi.shl.service.ShlRetrievalService;
import com.chanakya.hsapi.shl.service.ShlService;
import com.chanakya.hsapi.storage.S3PayloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Validates GET /shl/{id} snapshot retrieval against PSHD specification Flow 3.
 */
class SnapshotRetrievalTest {

    private ShlLinkRepository linkRepo;
    private S3PayloadService s3;
    private ShlService shlService;
    private AuditService auditService;
    private ShlRetrievalService retrievalService;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        linkRepo = mock(ShlLinkRepository.class);
        s3 = mock(S3PayloadService.class);
        shlService = mock(ShlService.class);
        auditService = mock(AuditService.class);
        var fieldEncryption = mock(FieldEncryptionService.class);
        var encryption = mock(EncryptionService.class);
        var bundleBuilder = mock(FhirBundleBuilder.class);
        var fhirSerialization = mock(FhirSerializationService.class);
        var crosswalk = mock(PatientCrosswalkService.class);
        var pdfGeneration = mock(PdfGenerationService.class);

        retrievalService = new ShlRetrievalService(linkRepo, s3, shlService, auditService,
            fieldEncryption, encryption, bundleBuilder, fhirSerialization, crosswalk, pdfGeneration);

        request = new MockHttpServletRequest();
    }

    private ShlLinkDocument createActiveSnapshotLink() {
        var link = new ShlLinkDocument();
        link.setId("link-001");
        link.setEnterpriseId("ENT-001");
        link.setFlag(ShlFlag.U);
        link.setStatus("active");
        link.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        link.setS3Key("shl/ENT-001/link-001.jwe");
        return link;
    }

    @Test
    void retrieveSnapshot_activeLink_returnsJwe() {
        var link = createActiveSnapshotLink();
        when(linkRepo.findById("link-001")).thenReturn(Optional.of(link));
        when(s3.downloadJwe("shl/ENT-001/link-001.jwe")).thenReturn("eyJhbGciOiJkaXIi.test-jwe");

        String jwe = retrievalService.retrieveSnapshot("link-001", "Dr. Smith", request);

        assertNotNull(jwe, "Active snapshot link should return JWE");
        assertEquals("eyJhbGciOiJkaXIi.test-jwe", jwe);
    }

    @Test
    void retrieveSnapshot_revokedLink_returnsNull() {
        var link = createActiveSnapshotLink();
        link.setStatus("revoked");
        when(linkRepo.findById("link-001")).thenReturn(Optional.of(link));

        String jwe = retrievalService.retrieveSnapshot("link-001", "Dr. Smith", request);

        assertNull(jwe, "Revoked link must return null (404)");
        verify(auditService).logShlAction(eq("link-001"), eq("ENT-001"),
            eq(ShlAuditAction.LINK_ACCESS_REVOKED), eq("Dr. Smith"), isNull(), eq(request));
    }

    @Test
    void retrieveSnapshot_expiredLink_returnsNull() {
        var link = createActiveSnapshotLink();
        link.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(linkRepo.findById("link-001")).thenReturn(Optional.of(link));

        String jwe = retrievalService.retrieveSnapshot("link-001", "Dr. Smith", request);

        assertNull(jwe, "Expired link must return null (404)");
        verify(auditService).logShlAction(eq("link-001"), eq("ENT-001"),
            eq(ShlAuditAction.LINK_ACCESS_EXPIRED), eq("Dr. Smith"), isNull(), eq(request));
    }

    @Test
    void retrieveSnapshot_notFound_returnsNull() {
        when(linkRepo.findById("nonexistent")).thenReturn(Optional.empty());

        String jwe = retrievalService.retrieveSnapshot("nonexistent", "Dr. Smith", request);

        assertNull(jwe, "Missing link must return null (404)");
        verify(auditService).logShlAction(eq("nonexistent"), isNull(),
            eq(ShlAuditAction.LINK_DENIED), eq("Dr. Smith"),
            eq(Map.of("reason", "not_found")), eq(request));
    }

    @Test
    void retrieveSnapshot_liveFlagLink_returnsNull() {
        var link = createActiveSnapshotLink();
        link.setFlag(ShlFlag.L); // Live flag, not snapshot
        when(linkRepo.findById("link-001")).thenReturn(Optional.of(link));

        String jwe = retrievalService.retrieveSnapshot("link-001", "Dr. Smith", request);

        assertNull(jwe, "L-flag link accessed via GET must be denied");
        verify(auditService).logShlAction(eq("link-001"), eq("ENT-001"),
            eq(ShlAuditAction.LINK_DENIED), eq("Dr. Smith"),
            eq(Map.of("reason", "wrong_method_for_flag")), eq(request));
    }

    @Test
    void retrieveSnapshot_pushesAccessRecord() {
        var link = createActiveSnapshotLink();
        when(linkRepo.findById("link-001")).thenReturn(Optional.of(link));
        when(s3.downloadJwe("shl/ENT-001/link-001.jwe")).thenReturn("test-jwe");

        retrievalService.retrieveSnapshot("link-001", "Dr. Smith", request);

        verify(shlService).pushAccessRecord(eq("link-001"), argThat(record ->
            "Dr. Smith".equals(record.recipient()) && "ACCESSED".equals(record.action())));
    }

    @Test
    void retrieveSnapshot_auditIncludesContentHash() {
        var link = createActiveSnapshotLink();
        when(linkRepo.findById("link-001")).thenReturn(Optional.of(link));
        when(s3.downloadJwe("shl/ENT-001/link-001.jwe")).thenReturn("test-jwe-content");

        retrievalService.retrieveSnapshot("link-001", "Dr. Smith", request);

        verify(auditService).logShlAction(eq("link-001"), eq("ENT-001"),
            eq(ShlAuditAction.LINK_ACCESSED), eq("Dr. Smith"),
            argThat(detail -> detail != null && detail.containsKey("contentHash")
                && detail.get("contentHash") instanceof String hash && hash.length() == 64),
            eq(request));
    }

    @Test
    void retrieveSnapshot_revokedLink_pushesAccessRecord() {
        var link = createActiveSnapshotLink();
        link.setStatus("revoked");
        when(linkRepo.findById("link-001")).thenReturn(Optional.of(link));

        retrievalService.retrieveSnapshot("link-001", "Dr. Smith", request);

        verify(shlService).pushAccessRecord(eq("link-001"), argThat(record ->
            "Dr. Smith".equals(record.recipient()) && "ACCESS_REVOKED".equals(record.action())));
    }
}
