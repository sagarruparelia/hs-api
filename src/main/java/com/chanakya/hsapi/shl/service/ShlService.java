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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
public class ShlService {

    private static final Logger log = LoggerFactory.getLogger(ShlService.class);
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

    public ShlService(ShlLinkRepository linkRepository, KeyGenerationService keyGen,
                      EncryptionService encryption, FieldEncryptionService fieldEncryption,
                      FhirBundleBuilder bundleBuilder, FhirSerializationService fhirSerialization,
                      S3PayloadService s3, PatientCrosswalkService crosswalk,
                      ShlinkBuilder shlinkBuilder, AuditService auditService,
                      MongoTemplate mongoTemplate, PdfGenerationService pdfGeneration) {
        this.linkRepository = linkRepository;
        this.keyGen = keyGen;
        this.encryption = encryption;
        this.fieldEncryption = fieldEncryption;
        this.bundleBuilder = bundleBuilder;
        this.fhirSerialization = fhirSerialization;
        this.s3 = s3;
        this.crosswalk = crosswalk;
        this.shlinkBuilder = shlinkBuilder;
        this.auditService = auditService;
        this.mongoTemplate = mongoTemplate;
        this.pdfGeneration = pdfGeneration;
    }

    public List<ShlLinkResponse> search(ShlSearchRequest req) {
        return linkRepository.findByEnterpriseId(req.idValue()).stream()
            .map(link -> {
                try {
                    return toResponse(link);
                } catch (Exception e) {
                    log.warn("Skipping link {} — decryption failed: {}", link.getId(), e.getMessage());
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    public ShlLinkResponse get(ShlSearchRequest req) {
        var link = linkRepository.findById(req.hsid_uuid())
            .orElseThrow(() -> new NoSuchElementException("Link not found: " + req.hsid_uuid()));
        return toResponse(link);
    }

    public ShlLinkResponse preview(ShlSearchRequest req) {
        return get(req);
    }

    public ShlCountsResponse counts(ShlSearchRequest req) {
        var link = linkRepository.findById(req.hsid_uuid())
            .orElseThrow(() -> new NoSuchElementException("Link not found"));

        String patientId = crosswalk.resolveHealthLakePatientId(link.getEnterpriseId());
        Map<String, Integer> counts = Map.of(); // Counts from HealthLake would go here
        return new ShlCountsResponse(counts, 0);
    }

    public ShlAccessHistoryResponse history(ShlSearchRequest req) {
        var link = linkRepository.findById(req.hsid_uuid())
            .orElseThrow(() -> new NoSuchElementException("Link not found"));
        return new ShlAccessHistoryResponse(link.getId(), link.getAccessHistory());
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

        boolean isLive = "LIVE".equalsIgnoreCase(req.mode());

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

        return new ShlCreateResponse(linkId, shlinkUri, shlinkUri, expiresAt);
    }

    public void revoke(ShlRevokeRequest req, HttpServletRequest httpRequest) {
        var link = linkRepository.findById(req.hsid_uuid())
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

    private ShlLinkResponse toResponse(ShlLinkDocument link) {
        String rawKey = fieldEncryption.decrypt(link.getEncryptionKey());
        String shlinkUri = shlinkBuilder.buildShlinkUri(
            link.getId(), link.getFlag(), rawKey,
            link.getExpiresAt().getEpochSecond(), link.getLabel());

        return new ShlLinkResponse(
            link.getId(), link.getLabel(),
            link.getMode(), link.getFlag(),
            link.getEffectiveStatus(),
            shlinkUri, shlinkUri,
            link.getExpiresAt(), link.getCreatedAt(),
            link.getSelectedResources(), link.isIncludePdf()
        );
    }
}
