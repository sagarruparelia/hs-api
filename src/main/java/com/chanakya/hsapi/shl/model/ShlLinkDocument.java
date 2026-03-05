package com.chanakya.hsapi.shl.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document("shl_links")
public class ShlLinkDocument {

    @Id
    private String id;
    private String enterpriseId;
    private String label;
    private ShlMode mode;
    private String flag;
    private String encryptionKey;
    private List<FhirResourceType> selectedResources;
    private boolean includePdf;
    private String patientName;
    private Instant expiresAt;
    private ShlStatus status;
    private String s3Key;
    private Instant createdAt;
    private List<AccessRecord> accessHistory = new ArrayList<>();

    public ShlLinkDocument() {}

    public String getEffectiveStatus() {
        if (status == ShlStatus.REVOKED) return "REVOKED";
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return "EXPIRED";
        return "ACTIVE";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public ShlMode getMode() { return mode; }
    public void setMode(ShlMode mode) { this.mode = mode; }
    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    public List<FhirResourceType> getSelectedResources() { return selectedResources; }
    public void setSelectedResources(List<FhirResourceType> selectedResources) { this.selectedResources = selectedResources; }
    public boolean isIncludePdf() { return includePdf; }
    public void setIncludePdf(boolean includePdf) { this.includePdf = includePdf; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public ShlStatus getStatus() { return status; }
    public void setStatus(ShlStatus status) { this.status = status; }
    public String getS3Key() { return s3Key; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<AccessRecord> getAccessHistory() { return accessHistory; }
    public void setAccessHistory(List<AccessRecord> accessHistory) { this.accessHistory = accessHistory; }
}
