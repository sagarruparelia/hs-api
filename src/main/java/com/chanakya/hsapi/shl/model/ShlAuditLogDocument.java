package com.chanakya.hsapi.shl.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

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

    public ShlAuditLogDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLinkId() { return linkId; }
    public void setLinkId(String linkId) { this.linkId = linkId; }
    public String getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
    public ShlAuditAction getAction() { return action; }
    public void setAction(ShlAuditAction action) { this.action = action; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
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
