package com.chanakya.hsapi.audit;

import com.chanakya.hsapi.shl.model.FhirResourceType;
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
@Document("audit_log")
public class AuditLogDocument {

    @Id
    private String id;
    private String enterpriseId;
    private String action;
    private FhirResourceType resourceType;
    private String resourceId;
    private Map<String, Object> detail;
    private String ipAddress;
    private String userAgent;
    private String requestId;
    private String consumerId;
    private String source;
    private Instant timestamp;
}
