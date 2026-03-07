package com.chanakya.hsapi.shl.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
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
    private ShlMode mode;
    private ShlFlag flag;
    private String encryptionKey;
    private List<String> selectedResources;
    private boolean includePdf;
    private String patientName;
    @Indexed(name = "idx_expiresAt")
    private Instant expiresAt;
    private ShlStatus status;
    private String s3Key;
    private Instant createdAt;
    @Field("accessHistory")
    private List<AccessRecord> accessHistory = new ArrayList<>();

    public ShlStatus getEffectiveStatus() {
        if (status == ShlStatus.REVOKED) return ShlStatus.REVOKED;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return ShlStatus.EXPIRED;
        return ShlStatus.ACTIVE;
    }
}
