package com.chanakya.hsapi.shl.service;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.crypto.EncryptionService;
import com.chanakya.hsapi.crypto.FieldEncryptionService;
import com.chanakya.hsapi.crypto.KeyGenerationService;
import com.chanakya.hsapi.fhir.FhirBundleBuilder;
import com.chanakya.hsapi.fhir.FhirSerializationService;
import com.chanakya.hsapi.pdf.PdfGenerationService;
import com.chanakya.hsapi.shl.dto.*;
import com.chanakya.hsapi.shl.model.*;
import com.chanakya.hsapi.shl.repository.ShlLinkRepository;
import com.chanakya.hsapi.storage.S3PayloadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RequiredArgsConstructor
@Service
public class ShlService {

    private static final Duration MIN_EXPIRY = Duration.ofMinutes(5);
    private static final Duration MAX_EXPIRY = Duration.ofDays(365);

    private final ShlLinkRepository linkRepository;
    private final KeyGenerationService keyGen;
    private final EncryptionService encryption;
    private final FieldEncryptionService fieldEncryption;
    private final FhirBundleBuilder bundleBuilder;
    private final FhirSerializationService fhirSerialization;
    private final S3PayloadService s3;
    private final PatientCrosswalkService crosswalk;
    private final ShlinkBuilder shlinkBuilder;
    private final AuditService auditService;
    private final MongoTemplate mongoTemplate;
    private final PdfGenerationService pdfGeneration;

    public List<ShlLinkResponse> search(ShlSearchRequest req) {
        return linkRepository.findByEnterpriseId(req.idValue()).stream()
            .map(link -> {
                try {
                    return toResponse(link, false);
                } catch (Exception e) {
                    log.warn("Skipping link {} — decryption failed: {}", link.getId(), e.getMessage());
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    public ShlLinkResponse get(ShlSearchRequest req) {
        var link = linkRepository.findById(req.linkId())
            .orElseThrow(() -> new NoSuchElementException("Link not found: " + req.linkId()));
        return toResponse(link, true);
    }

    public String preview(ShlSearchRequest req) {
        var link = linkRepository.findById(req.linkId())
            .orElseThrow(() -> new NoSuchElementException("Link not found: " + req.linkId()));

        if ("snapshot".equals(link.getMode()) && link.getS3Key() != null) {
            // Snapshot: download JWE from S3, decrypt, return raw FHIR Bundle JSON
            String jwe = s3.downloadJwe(link.getS3Key());
            String rawKey = fieldEncryption.decrypt(link.getEncryptionKey());
            return encryption.decryptJwe(jwe, rawKey);
        } else {
            // Live mode: build fresh bundle from HealthLake
            String patientId = crosswalk.resolveHealthLakePatientId(link.getEnterpriseId());
            byte[] pdfBytes = null;
            if (link.isIncludePdf()) {
                pdfBytes = pdfGeneration.generatePatientSummaryPdf(link.getPatientName(), Map.of());
            }
            var bundle = bundleBuilder.buildPatientSharedBundle(
                patientId, link.getSelectedResources(), link.isIncludePdf(), pdfBytes, link.getPatientName());
            return fhirSerialization.toJson(bundle);
        }
    }

    public ShlCreateResponse create(ShlCreateRequest req, HttpServletRequest httpRequest) {
        // Validate expiresAt
        if (req.expiresAt() == null || req.expiresAt().isBlank()) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(req.expiresAt());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt must be a valid ISO-8601 timestamp");
        }
        Instant now = Instant.now();
        if (expiresAt.isBefore(now.plus(MIN_EXPIRY))) {
            throw new IllegalArgumentException("expiresAt must be at least 5 minutes from now");
        }
        if (expiresAt.isAfter(now.plus(MAX_EXPIRY))) {
            throw new IllegalArgumentException("expiresAt must be at most 365 days from now");
        }

        boolean isLive = "live".equalsIgnoreCase(req.mode());

        if (req.includePdf() && (req.patientName() == null || req.patientName().isBlank())) {
            throw new IllegalArgumentException("patientName is required when includePdf is true");
        }

        // Generate link ID and AES key
        String linkId = keyGen.generateLinkId();
        String rawKey = keyGen.generateAesKeyBase64Url();
        String encryptedKey = fieldEncryption.encrypt(rawKey);

        // Resolve patient
        String patientId = crosswalk.resolveHealthLakePatientId(req.idValue());

        String s3Key = null;
        if (!isLive) {
            // Generate PDF if requested
            byte[] pdfBytes = null;
            if (req.includePdf()) {
                pdfBytes = pdfGeneration.generatePatientSummaryPdf(req.patientName(), Map.of());
            }

            // Snapshot mode: build bundle, encrypt, upload to S3
            var bundle = bundleBuilder.buildPatientSharedBundle(
                patientId, req.selectedResources(), req.includePdf(), pdfBytes, req.patientName());
            String bundleJson = fhirSerialization.toJson(bundle);
            String jwe = encryption.encryptToJwe(bundleJson, rawKey);
            s3Key = s3.buildS3Key(req.idValue(), linkId);
            s3.uploadJwe(s3Key, jwe);
        }

        // Create link document
        var link = new ShlLinkDocument();
        link.setId(linkId);
        link.setEnterpriseId(req.idValue());
        link.setLabel(req.label());
        link.setMode(isLive ? "live" : "snapshot");
        link.setFlag(isLive ? ShlFlag.L : ShlFlag.U);
        link.setEncryptionKey(encryptedKey);
        link.setSelectedResources(req.selectedResources());
        link.setIncludePdf(req.includePdf());
        link.setPatientName(req.patientName());
        link.setExpiresAt(expiresAt);
        link.setStatus("active");
        link.setS3Key(s3Key);
        link.setCreatedAt(now);
        linkRepository.save(link);

        // Build shlink URI
        String shlinkUri = shlinkBuilder.buildShlinkUri(
            linkId, link.getFlag(), rawKey, expiresAt.getEpochSecond(), req.label());

        // Audit
        auditService.logShlAction(linkId, req.idValue(), ShlAuditAction.LINK_CREATED,
            null, Map.of("mode", link.getMode(), "flag", link.getFlag()), httpRequest);

        return new ShlCreateResponse(linkId, shlinkUri, expiresAt);
    }

    public void revoke(ShlRevokeRequest req, HttpServletRequest httpRequest) {
        var link = linkRepository.findById(req.linkId())
            .orElseThrow(() -> new NoSuchElementException("Link not found"));

        link.setStatus("revoked");
        linkRepository.save(link);

        auditService.logShlAction(link.getId(), link.getEnterpriseId(), ShlAuditAction.LINK_REVOKED,
            null, Map.of(), httpRequest);
    }

    public void pushAccessRecord(String linkId, AccessRecord record) {
        Query query = Query.query(Criteria.where("_id").is(linkId));
        Update update = new Update().push("accessHistory").slice(-50).each(record);
        mongoTemplate.updateFirst(query, update, ShlLinkDocument.class);
    }

    private ShlLinkResponse toResponse(ShlLinkDocument link, boolean includeHistory) {
        String rawKey = fieldEncryption.decrypt(link.getEncryptionKey());
        String shlinkUri = shlinkBuilder.buildShlinkUri(
            link.getId(), link.getFlag(), rawKey,
            link.getExpiresAt().getEpochSecond(), link.getLabel());

        List<AccessRecord> history = includeHistory ? link.getAccessHistory() : null;

        return new ShlLinkResponse(
            link.getId(), link.getLabel(),
            link.getMode(), link.getFlag(),
            link.getEffectiveStatus(),
            shlinkUri,
            link.getExpiresAt(), link.getCreatedAt(),
            link.getSelectedResources(), link.isIncludePdf(),
            history
        );
    }
}
