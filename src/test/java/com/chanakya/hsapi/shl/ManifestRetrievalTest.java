package com.chanakya.hsapi.shl;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.crypto.EncryptionService;
import com.chanakya.hsapi.crypto.FieldEncryptionService;
import com.chanakya.hsapi.fhir.FhirBundleBuilder;
import com.chanakya.hsapi.fhir.FhirSerializationService;
import com.chanakya.hsapi.pdf.PdfGenerationService;
import com.chanakya.hsapi.shl.dto.ManifestResponse;
import com.chanakya.hsapi.shl.model.*;
import com.chanakya.hsapi.shl.repository.ShlLinkRepository;
import com.chanakya.hsapi.shl.service.ShlRetrievalService;
import com.chanakya.hsapi.shl.service.ShlService;
import com.chanakya.hsapi.storage.S3PayloadService;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Validates POST /shl/{id} manifest retrieval against PSHD specification Flow 5.
 */
class ManifestRetrievalTest {

    private ShlLinkRepository linkRepo;
    private ShlService shlService;
    private AuditService auditService;
    private FieldEncryptionService fieldEncryption;
    private EncryptionService encryption;
    private FhirBundleBuilder bundleBuilder;
    private FhirSerializationService fhirSerialization;
    private PatientCrosswalkService crosswalk;
    private PdfGenerationService pdfGeneration;
    private ShlRetrievalService retrievalService;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        linkRepo = mock(ShlLinkRepository.class);
        var s3 = mock(S3PayloadService.class);
        shlService = mock(ShlService.class);
        auditService = mock(AuditService.class);
        fieldEncryption = mock(FieldEncryptionService.class);
        encryption = mock(EncryptionService.class);
        bundleBuilder = mock(FhirBundleBuilder.class);
        fhirSerialization = mock(FhirSerializationService.class);
        crosswalk = mock(PatientCrosswalkService.class);
        pdfGeneration = mock(PdfGenerationService.class);

        retrievalService = new ShlRetrievalService(linkRepo, s3, shlService, auditService,
            fieldEncryption, encryption, bundleBuilder, fhirSerialization, crosswalk, pdfGeneration);

        request = new MockHttpServletRequest();
    }

    private ShlLinkDocument createActiveLiveLink() {
        var link = new ShlLinkDocument();
        link.setId("link-live-001");
        link.setEnterpriseId("ENT-001");
        link.setFlag(ShlFlag.L);
        link.setMode(ShlMode.LIVE);
        link.setStatus(ShlStatus.ACTIVE);
        link.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        link.setEncryptionKey("encrypted-key-value");
        link.setSelectedResources(List.of("Condition", "MedicationRequest"));
        link.setIncludePdf(false);
        return link;
    }

    private void setupLiveFlowMocks(ShlLinkDocument link) {
        when(crosswalk.resolveHealthLakePatientId("ENT-001")).thenReturn("HL-PAT-123");
        when(bundleBuilder.buildPatientSharedBundle(eq("HL-PAT-123"), eq(link.getSelectedResources())))
            .thenReturn(new Bundle());
        when(fhirSerialization.toJson(any(Bundle.class))).thenReturn("{\"resourceType\":\"Bundle\"}");
        when(fieldEncryption.decrypt("encrypted-key-value")).thenReturn("raw-key-base64url");
        when(encryption.encryptToJwe(anyString(), eq("raw-key-base64url"))).thenReturn("header.key.iv.ct.tag");
    }

    @Test
    void retrieveManifest_activeLink_returnsCanChange() {
        var link = createActiveLiveLink();
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));
        setupLiveFlowMocks(link);

        ManifestResponse manifest = retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        assertNotNull(manifest);
        assertEquals("can-change", manifest.status(),
            "Active live link must return status=can-change");
    }

    @Test
    void retrieveManifest_activeLink_hasOneFile() {
        var link = createActiveLiveLink();
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));
        setupLiveFlowMocks(link);

        ManifestResponse manifest = retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        assertNotNull(manifest.files());
        assertEquals(1, manifest.files().size(), "Manifest must contain exactly 1 file");
    }

    @Test
    void retrieveManifest_fileContentType_isFhirJson() {
        var link = createActiveLiveLink();
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));
        setupLiveFlowMocks(link);

        ManifestResponse manifest = retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        assertEquals("application/fhir+json", manifest.files().getFirst().contentType(),
            "Manifest file contentType must be application/fhir+json per HL7 SHL spec");
    }

    @Test
    void retrieveManifest_fileEmbedded_isJwe() {
        var link = createActiveLiveLink();
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));
        setupLiveFlowMocks(link);

        ManifestResponse manifest = retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        String embedded = manifest.files().getFirst().embedded();
        assertNotNull(embedded, "Embedded JWE must not be null");
        assertEquals(5, embedded.split("\\.").length,
            "Embedded value must be JWE compact serialization (5 dot-separated segments)");
    }

    @Test
    void retrieveManifest_revokedLink_returnsNoLongerValid() {
        var link = createActiveLiveLink();
        link.setStatus(ShlStatus.REVOKED);
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));

        ManifestResponse manifest = retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        assertNotNull(manifest);
        assertEquals("no-longer-valid", manifest.status(),
            "Revoked link must return status=no-longer-valid");
        assertTrue(manifest.files().isEmpty(),
            "Revoked link must return empty files array");
    }

    @Test
    void retrieveManifest_expiredLink_returnsNoLongerValid() {
        var link = createActiveLiveLink();
        link.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));

        ManifestResponse manifest = retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        assertNotNull(manifest);
        assertEquals("no-longer-valid", manifest.status(),
            "Expired link must return status=no-longer-valid");
        assertTrue(manifest.files().isEmpty(),
            "Expired link must return empty files array");
    }

    @Test
    void retrieveManifest_notFound_returnsNull() {
        when(linkRepo.findById("nonexistent")).thenReturn(Optional.empty());

        ManifestResponse manifest = retrievalService.retrieveManifest("nonexistent", "app@example.com", request);

        assertNull(manifest, "Missing link must return null");
        verify(auditService).logShlAction(eq("nonexistent"), isNull(),
            eq(ShlAuditAction.LINK_DENIED), eq("app@example.com"),
            eq(Map.of("reason", "not_found")), eq(request));
    }

    @Test
    void retrieveManifest_snapshotFlagLink_returnsNull() {
        var link = createActiveLiveLink();
        link.setFlag(ShlFlag.U); // Snapshot flag
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));

        ManifestResponse manifest = retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        assertNull(manifest, "U-flag link accessed via POST must be denied");
        verify(auditService).logShlAction(eq("link-live-001"), eq("ENT-001"),
            eq(ShlAuditAction.LINK_DENIED), eq("app@example.com"),
            eq(Map.of("reason", "wrong_method_for_flag")), eq(request));
    }

    @Test
    void retrieveManifest_decryptsStoredKey() {
        var link = createActiveLiveLink();
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));
        setupLiveFlowMocks(link);

        retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        verify(fieldEncryption).decrypt("encrypted-key-value");
        verify(encryption).encryptToJwe(anyString(), eq("raw-key-base64url"));
    }

    @Test
    void retrieveManifest_buildsFreshBundle() {
        var link = createActiveLiveLink();
        when(linkRepo.findById("link-live-001")).thenReturn(Optional.of(link));
        setupLiveFlowMocks(link);

        retrievalService.retrieveManifest("link-live-001", "app@example.com", request);

        verify(crosswalk).resolveHealthLakePatientId("ENT-001");
        verify(bundleBuilder).buildPatientSharedBundle(
            eq("HL-PAT-123"), eq(link.getSelectedResources()));
    }
}
