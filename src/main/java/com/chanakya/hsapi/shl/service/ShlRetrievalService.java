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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@Slf4j
@RequiredArgsConstructor
@Service
public class ShlRetrievalService {

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

    public String retrieveSnapshot(String linkId, String recipient, HttpServletRequest request) {
        var link = validateLink(linkId, recipient, request, ShlFlag::isSnapshot);
        if (link == null) return null;

        if (handleInactiveStatus(link, linkId, recipient, request)) return null;

        String jwe = s3.downloadJwe(link.getS3Key());

        shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESSED"));
        auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESSED,
            recipient, Map.of("contentHash", sha256(jwe)), request);

        return jwe;
    }

    public ManifestResponse retrieveManifest(String linkId, String recipient, HttpServletRequest request) {
        var link = validateLink(linkId, recipient, request, ShlFlag::isLive);
        if (link == null) return null;

        if (handleInactiveStatus(link, linkId, recipient, request)) {
            return new ManifestResponse("no-longer-valid", List.of());
        }

        String patientId = crosswalk.resolveHealthLakePatientId(link.getEnterpriseId());
        var bundle = bundleBuilder.buildPatientSharedBundle(patientId, link.getSelectedResources());
        if (link.isIncludePdf()) {
            byte[] pdfBytes = pdfGeneration.generatePatientSummaryPdf(bundle, link.getPatientName());
            bundleBuilder.addPdfDocumentReference(bundle, pdfBytes, link.getPatientName());
        }
        String bundleJson = fhirSerialization.toJson(bundle);

        String rawKey = fieldEncryption.decrypt(link.getEncryptionKey());
        String jwe = encryption.encryptToJwe(bundleJson, rawKey);

        shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESSED"));
        auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESSED,
            recipient, Map.of("contentHash", sha256(jwe)), request);

        var file = new ManifestResponse.ManifestFile("application/fhir+json", jwe);
        return new ManifestResponse("can-change", List.of(file));
    }

    private ShlLinkDocument validateLink(String linkId, String recipient,
                                          HttpServletRequest request, Predicate<ShlFlag> flagCheck) {
        var link = linkRepository.findById(linkId).orElse(null);
        if (link == null) {
            auditService.logShlAction(linkId, null, ShlAuditAction.LINK_DENIED,
                recipient, Map.of("reason", "not_found"), request);
            return null;
        }
        if (!flagCheck.test(link.getFlag())) {
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_DENIED,
                recipient, Map.of("reason", "wrong_method_for_flag"), request);
            return null;
        }
        return link;
    }

    private boolean handleInactiveStatus(ShlLinkDocument link, String linkId,
                                          String recipient, HttpServletRequest request) {
        ShlStatus effectiveStatus = link.getEffectiveStatus();
        if (effectiveStatus == ShlStatus.REVOKED) {
            shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESS_REVOKED"));
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESS_REVOKED,
                recipient, null, request);
            return true;
        }
        if (effectiveStatus == ShlStatus.EXPIRED) {
            shlService.pushAccessRecord(linkId, AccessRecord.of(recipient, "ACCESS_EXPIRED"));
            auditService.logShlAction(linkId, link.getEnterpriseId(), ShlAuditAction.LINK_ACCESS_EXPIRED,
                recipient, null, request);
            return true;
        }
        return false;
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
