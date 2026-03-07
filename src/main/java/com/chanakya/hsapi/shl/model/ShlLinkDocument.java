package com.chanakya.hsapi.shl.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
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

    public String getEffectiveStatus() {
        if ("revoked".equalsIgnoreCase(status)) return "revoked";
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return "expired";
        return "active";
    }

    public void setMode(String mode) { this.mode = mode; }

    public void setMode(ShlMode mode) { this.mode = mode.name().toLowerCase(); }

    public void setStatus(String status) { this.status = status; }

    public void setStatus(ShlStatus status) { this.status = status.name().toLowerCase(); }
}
