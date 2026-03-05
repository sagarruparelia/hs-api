package com.chanakya.hsapi.audit;

import com.chanakya.hsapi.shl.model.FhirResourceType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

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

    public AuditLogDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public FhirResourceType getResourceType() { return resourceType; }
    public void setResourceType(FhirResourceType resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public Map<String, Object> getDetail() { return detail; }
    public void setDetail(Map<String, Object> detail) { this.detail = detail; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getConsumerId() { return consumerId; }
    public void setConsumerId(String consumerId) { this.consumerId = consumerId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
