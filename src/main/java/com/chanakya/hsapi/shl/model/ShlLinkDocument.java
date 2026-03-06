package com.chanakya.hsapi.shl.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document("shl_links")
public class ShlLinkDocument {

    @Id
    private String id;
    private String enterpriseId;
    private String label;
    private String mode;
    private String flag;
    private String encryptionKey;
    private List<String> selectedResources;
    private boolean includePdf;
    private String patientName;
    private Instant expiresAt;
    private String status;
    private String s3Key;
    private Instant createdAt;
    @Field("accessHistory")
    private List<AccessRecord> accessHistory = new ArrayList<>();

    public ShlLinkDocument() {}

    public String getEffectiveStatus() {
        if ("revoked".equalsIgnoreCase(status)) return "revoked";
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return "expired";
        return "active";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public void setMode(ShlMode mode) { this.mode = mode.name().toLowerCase(); }
    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    public List<String> getSelectedResources() { return selectedResources; }
    public void setSelectedResources(List<String> selectedResources) { this.selectedResources = selectedResources; }
    public boolean isIncludePdf() { return includePdf; }
    public void setIncludePdf(boolean includePdf) { this.includePdf = includePdf; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public void setStatus(ShlStatus status) { this.status = status.name().toLowerCase(); }
    public String getS3Key() { return s3Key; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<AccessRecord> getAccessHistory() { return accessHistory; }
    public void setAccessHistory(List<AccessRecord> accessHistory) { this.accessHistory = accessHistory; }
}
