package com.chanakya.hsapi.shl.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@Document("shl_audit_log")
@CompoundIndexes({
    @CompoundIndex(name = "idx_linkId_timestamp", def = "{'linkId': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "idx_enterpriseId_timestamp", def = "{'enterpriseId': 1, 'timestamp': -1}")
})
public class ShlAuditLogDocument {

    @Id
    private String id;
    private String linkId;
    private String enterpriseId;
    private ShlAuditAction action;
    private String recipient;
    private Map<String, Object> detail;
    private String ipAddress;
    private String userAgent;
    private String requestId;
    private String consumerId;
    private String source;
    private Instant timestamp;
}
