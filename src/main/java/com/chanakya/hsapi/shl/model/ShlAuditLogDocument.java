package com.chanakya.hsapi.shl.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@Document("shl_audit_log")
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
