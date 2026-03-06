package com.chanakya.hsapi.shl.service;

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
import com.chanakya.hsapi.storage.S3PayloadService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class ShlRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(ShlRetrievalService.class);

    private final ShlLinkRepository linkRepository;
    private final S3PayloadService s3;
    private final ShlService shlService;
    private final AuditService auditService;
    private final FieldEncryptionService fieldEncryption;
    private final EncryptionService encryption;
    private final FhirBundleBuilder bundleBuilder;
    private final FhirSerializationService fhirSerialization;
    private final PatientCrosswalkService crosswalk;
    private final PdfGenerationService pdfGeneration;

    public ShlRetrievalService(ShlLinkRepository linkRepository, S3PayloadService s3,
                                ShlService shlService, AuditService auditService,
                                FieldEncryptionService fieldEncryption, EncryptionService encryption,
                                FhirBundleBuilder bundleBuilder, FhirSerializationService fhirSerialization,
                                PatientCrosswalkService crosswalk, PdfGenerationService pdfGeneration) {
        this.linkRepository = linkRepository;
        this.s3 = s3;
        this.shlService = shlService;
        this.auditService = auditService;
        this.fieldEncryption = fieldEncryption;
        this.encryption = encryption;
        this.bundleBuilder = bundleBuilder;
        this.fhirSerialization = fhirSerialization;
        this.crosswalk = crosswalk;
        this.pdfGeneration = pdfGeneration;
    }

    public String retrieveSnapshot(String linkId, String recipient, HttpServletRequest request) {
        var link = linkRepository.findById(linkId).orElse(null);
        if (link == null) {
            auditService.logShlAction(linkId, null, ShlAuditAction.LINK_DENIED,
                recipient, Map.of("reason", "not_found"), request);
            return null;
        }

        // Guard: GET is only valid for snapshot (U flag) links
        if (!ShlFlag.isSnapshot(link.getFlag())) {
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_DENIED,
                recipient, Map.of("reason", "wrong_method_for_flag"), request);
            return null;
        }

        String effectiveStatus = link.getEffectiveStatus();
        if ("revoked".equals(effectiveStatus)) {
            shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESS_REVOKED"));
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESS_REVOKED,
                recipient, null, request);
            return null;
        }
        if ("expired".equals(effectiveStatus)) {
            shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESS_EXPIRED"));
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESS_EXPIRED,
                recipient, null, request);
            return null;
        }

        String jwe = s3.downloadJwe(link.getS3Key());

        shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESSED"));
        auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESSED,
            recipient, Map.of("contentHash", sha256(jwe)), request);

        return jwe;
    }

    public ManifestResponse retrieveManifest(String linkId, String recipient, HttpServletRequest request) {
        var link = linkRepository.findById(linkId).orElse(null);
        if (link == null) {
            auditService.logShlAction(linkId, null, ShlAuditAction.LINK_DENIED,
                recipient, Map.of("reason", "not_found"), request);
            return null;
        }

        // Guard: POST is only valid for live (L flag) links
        if (!ShlFlag.isLive(link.getFlag())) {
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_DENIED,
                recipient, Map.of("reason", "wrong_method_for_flag"), request);
            return null;
        }

        String effectiveStatus = link.getEffectiveStatus();
        if ("revoked".equals(effectiveStatus)) {
            shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESS_REVOKED"));
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESS_REVOKED,
                recipient, null, request);
            return new ManifestResponse("no-longer-valid", List.of());
        }
        if ("expired".equals(effectiveStatus)) {
            shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESS_EXPIRED"));
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESS_EXPIRED,
                recipient, null, request);
            return new ManifestResponse("no-longer-valid", List.of());
        }

        // Generate PDF if requested
        byte[] pdfBytes = null;
        if (link.isIncludePdf()) {
            pdfBytes = pdfGeneration.generatePatientSummaryPdf(link.getPatientName(), Map.of());
        }

        // Build fresh bundle for live mode
        String patientId = crosswalk.resolveHealthLakePatientId(link.getEnterpriseId());
        var bundle = bundleBuilder.buildPatientSharedBundle(
            patientId, link.getSelectedResources(), link.isIncludePdf(), pdfBytes, link.getPatientName());
        String bundleJson = fhirSerialization.toJson(bundle);

        // Decrypt the stored key to encrypt the bundle
        String rawKey = fieldEncryption.decrypt(link.getEncryptionKey());
        String jwe = encryption.encryptToJwe(bundleJson, rawKey);

        shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESSED"));
        auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESSED,
            recipient, Map.of("contentHash", sha256(jwe)), request);

        var file = new ManifestResponse.ManifestFile("application/jose", jwe);
        return new ManifestResponse("can-change", List.of(file));
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
