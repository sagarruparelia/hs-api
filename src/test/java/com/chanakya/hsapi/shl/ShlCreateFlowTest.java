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
import com.chanakya.hsapi.shl.dto.ShlCreateResponse;
import com.chanakya.hsapi.shl.model.*;
import com.chanakya.hsapi.shl.repository.ShlLinkRepository;
import com.chanakya.hsapi.shl.service.ShlService;
import com.chanakya.hsapi.shl.service.ShlinkBuilder;
import com.chanakya.hsapi.storage.S3PayloadService;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Validates SHL link creation flow for both snapshot (U flag) and live (L flag) modes
 * against architecture spec Flows 2 and 4.
 */
class ShlCreateFlowTest {

    private ShlLinkRepository linkRepo;
    private KeyGenerationService keyGen;
    private EncryptionService encryption;
    private FieldEncryptionService fieldEncryption;
    private FhirBundleBuilder bundleBuilder;
    private FhirSerializationService fhirSerialization;
    private S3PayloadService s3;
    private PatientCrosswalkService crosswalk;
    private ShlinkBuilder shlinkBuilder;
    private AuditService auditService;
    private PdfGenerationService pdfGeneration;
    private ShlService shlService;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        linkRepo = mock(ShlLinkRepository.class);
        keyGen = mock(KeyGenerationService.class);
        encryption = mock(EncryptionService.class);
        fieldEncryption = mock(FieldEncryptionService.class);
        bundleBuilder = mock(FhirBundleBuilder.class);
        fhirSerialization = mock(FhirSerializationService.class);
        s3 = mock(S3PayloadService.class);
        crosswalk = mock(PatientCrosswalkService.class);
        shlinkBuilder = mock(ShlinkBuilder.class);
        auditService = mock(AuditService.class);
        var mongoTemplate = mock(MongoTemplate.class);
        pdfGeneration = mock(PdfGenerationService.class);

        shlService = new ShlService(linkRepo, keyGen, encryption, fieldEncryption,
            bundleBuilder, fhirSerialization, s3, crosswalk, shlinkBuilder,
            auditService, mongoTemplate, pdfGeneration);

        httpRequest = new MockHttpServletRequest();

        // Default mocks
        when(keyGen.generateLinkId()).thenReturn("generated-link-id-43chars-base64url-aaaa");
        when(keyGen.generateAesKeyBase64Url()).thenReturn("rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q");
        when(fieldEncryption.encrypt(anyString())).thenReturn("encrypted-key");
        when(crosswalk.resolveHealthLakePatientId("ENT-001")).thenReturn("HL-PAT-123");
        when(bundleBuilder.buildPatientSharedBundle(anyString(), anyList(), anyBoolean(), any(), any()))
            .thenReturn(new Bundle());
        when(fhirSerialization.toJson(any(Bundle.class))).thenReturn("{\"resourceType\":\"Bundle\"}");
        when(encryption.encryptToJwe(anyString(), anyString())).thenReturn("encrypted.jwe.content.here.tag");
        when(s3.buildS3Key("ENT-001", "generated-link-id-43chars-base64url-aaaa"))
            .thenReturn("shl/ENT-001/generated-link-id-43chars-base64url-aaaa.jwe");
        when(shlinkBuilder.buildShlinkUri(anyString(), anyString(), anyString(), anyLong(), any()))
            .thenReturn("shlink:/base64payload");
        when(linkRepo.save(any(ShlLinkDocument.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ShlCreateRequest snapshotRequest() {
        return new ShlCreateRequest("EID", "ENT-001", "Test Link",
            Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of("Condition", "MedicationRequest"), false, null, "snapshot");
    }

    private ShlCreateRequest liveRequest() {
        return new ShlCreateRequest("EID", "ENT-001", "Live Link",
            Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of("Condition"), false, null, "live");
    }

    @Test
    void create_snapshot_uploadsJweToS3() {
        shlService.create(snapshotRequest(), httpRequest);

        verify(s3).uploadJwe(eq("shl/ENT-001/generated-link-id-43chars-base64url-aaaa.jwe"),
            eq("encrypted.jwe.content.here.tag"));
    }

    @Test
    void create_snapshot_flagIsU() {
        shlService.create(snapshotRequest(), httpRequest);

        ArgumentCaptor<ShlLinkDocument> captor = ArgumentCaptor.forClass(ShlLinkDocument.class);
        verify(linkRepo).save(captor.capture());
        assertEquals(ShlFlag.U, captor.getValue().getFlag(),
            "Snapshot mode must set flag to U");
    }

    @Test
    void create_snapshot_s3KeyFormat() {
        shlService.create(snapshotRequest(), httpRequest);

        ArgumentCaptor<ShlLinkDocument> captor = ArgumentCaptor.forClass(ShlLinkDocument.class);
        verify(linkRepo).save(captor.capture());
        assertEquals("shl/ENT-001/generated-link-id-43chars-base64url-aaaa.jwe",
            captor.getValue().getS3Key(),
            "S3 key format must be shl/{enterpriseId}/{linkId}.jwe");
    }

    @Test
    void create_live_noS3Upload() {
        shlService.create(liveRequest(), httpRequest);

        verify(s3, never()).uploadJwe(anyString(), anyString());
    }

    @Test
    void create_live_s3KeyIsNull() {
        shlService.create(liveRequest(), httpRequest);

        ArgumentCaptor<ShlLinkDocument> captor = ArgumentCaptor.forClass(ShlLinkDocument.class);
        verify(linkRepo).save(captor.capture());
        assertNull(captor.getValue().getS3Key(),
            "Live mode must have null s3Key (no upfront S3 upload)");
    }

    @Test
    void create_live_flagIsL() {
        shlService.create(liveRequest(), httpRequest);

        ArgumentCaptor<ShlLinkDocument> captor = ArgumentCaptor.forClass(ShlLinkDocument.class);
        verify(linkRepo).save(captor.capture());
        assertEquals(ShlFlag.L, captor.getValue().getFlag(),
            "Live mode must set flag to L");
    }

    @Test
    void create_returnsLinkIdAndShlinkUrl() {
        ShlCreateResponse response = shlService.create(snapshotRequest(), httpRequest);

        assertNotNull(response);
        assertEquals("generated-link-id-43chars-base64url-aaaa", response.linkId());
        assertEquals("shlink:/base64payload", response.shlinkUrl());
        assertNotNull(response.expiresAt());
    }

    @Test
    void create_encryptsKeyBeforeStorage() {
        shlService.create(snapshotRequest(), httpRequest);

        // Raw key is encrypted before storage
        verify(fieldEncryption).encrypt("rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q");

        // Stored document has encrypted key, not raw
        ArgumentCaptor<ShlLinkDocument> captor = ArgumentCaptor.forClass(ShlLinkDocument.class);
        verify(linkRepo).save(captor.capture());
        assertEquals("encrypted-key", captor.getValue().getEncryptionKey(),
            "MongoDB must store the encrypted key, not the raw key");
    }

    @Test
    void create_shlinkUri_usesRawKey() {
        shlService.create(snapshotRequest(), httpRequest);

        // SHLink URI must use the RAW key, not the encrypted one
        verify(shlinkBuilder).buildShlinkUri(
            eq("generated-link-id-43chars-base64url-aaaa"),
            eq("U"),
            eq("rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q"), // raw key
            anyLong(),
            eq("Test Link"));
    }

    @Test
    void create_auditsLinkCreated() {
        shlService.create(snapshotRequest(), httpRequest);

        verify(auditService).logShlAction(
            eq("generated-link-id-43chars-base64url-aaaa"),
            eq("ENT-001"),
            eq(ShlAuditAction.LINK_CREATED),
            isNull(),
            eq(Map.of("mode", "SNAPSHOT", "flag", "U")),
            eq(httpRequest));
    }

    @Test
    void create_resolvesPatientCrosswalk() {
        shlService.create(snapshotRequest(), httpRequest);

        verify(crosswalk).resolveHealthLakePatientId("ENT-001");
    }

    @Test
    void create_statusIsActive() {
        shlService.create(snapshotRequest(), httpRequest);

        ArgumentCaptor<ShlLinkDocument> captor = ArgumentCaptor.forClass(ShlLinkDocument.class);
        verify(linkRepo).save(captor.capture());
        assertEquals(ShlStatus.ACTIVE, captor.getValue().getStatus(),
            "New link must have status=active");
    }
}
