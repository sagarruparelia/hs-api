package com.chanakya.hsapi.audit;

import com.chanakya.hsapi.auth.ExternalAuthFilter;
import com.chanakya.hsapi.common.filter.RequestIdFilter;
import com.chanakya.hsapi.shl.model.FhirResourceType;
import com.chanakya.hsapi.shl.model.ShlAuditAction;
import com.chanakya.hsapi.shl.model.ShlAuditLogDocument;
import com.chanakya.hsapi.shl.repository.ShlAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ShlAuditLogRepository shlAuditLogRepository;

    public void logFhirQuery(String enterpriseId, FhirResourceType resourceType,
                             HttpServletRequest request) {
        var audit = new AuditLogDocument();
        audit.setEnterpriseId(enterpriseId);
        audit.setAction("fhir_query");
        audit.setResourceType(resourceType);
        audit.setIpAddress(getClientIp(request));
        audit.setUserAgent(request.getHeader("User-Agent"));
        audit.setRequestId((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR));
        audit.setConsumerId((String) request.getAttribute(ExternalAuthFilter.CONSUMER_ID_ATTR));
        audit.setSource("external");
        audit.setTimestamp(Instant.now());
        auditLogRepository.save(audit);
        log.debug("Audit: fhir_query for {} resourceType={}", enterpriseId, resourceType);
    }

    public void logShlAction(String linkId, String enterpriseId, ShlAuditAction action,
                             String recipient, Map<String, Object> detail,
                             HttpServletRequest request) {
        var audit = new ShlAuditLogDocument();
        audit.setLinkId(linkId);
        audit.setEnterpriseId(enterpriseId);
        audit.setAction(action);
        audit.setRecipient(recipient);
        audit.setDetail(detail);
        audit.setIpAddress(getClientIp(request));
        audit.setUserAgent(request.getHeader("User-Agent"));
        audit.setRequestId((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR));
        audit.setConsumerId(request.getAttribute(ExternalAuthFilter.CONSUMER_ID_ATTR) != null
            ? (String) request.getAttribute(ExternalAuthFilter.CONSUMER_ID_ATTR) : null);
        audit.setSource(request.getAttribute(ExternalAuthFilter.SOURCE_ATTR) != null
            ? (String) request.getAttribute(ExternalAuthFilter.SOURCE_ATTR) : "public");
        audit.setTimestamp(Instant.now());
        shlAuditLogRepository.save(audit);
        log.debug("SHL Audit: {} linkId={} recipient={}", action, linkId, recipient);
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
