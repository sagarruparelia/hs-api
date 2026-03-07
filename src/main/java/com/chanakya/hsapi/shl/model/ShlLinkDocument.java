package com.chanakya.hsapi.shl.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Document("shl_links")
@CompoundIndex(name = "idx_enterpriseId_status", def = "{'enterpriseId': 1, 'status': 1}")
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
    @Indexed(name = "idx_expiresAt")
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
