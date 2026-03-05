package com.chanakya.hsapi.shl.repository;

import com.chanakya.hsapi.shl.model.ShlAuditLogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ShlAuditLogRepository extends MongoRepository<ShlAuditLogDocument, String> {

    List<ShlAuditLogDocument> findByLinkIdOrderByTimestampDesc(String linkId);

    List<ShlAuditLogDocument> findByEnterpriseIdOrderByTimestampDesc(String enterpriseId);
}
