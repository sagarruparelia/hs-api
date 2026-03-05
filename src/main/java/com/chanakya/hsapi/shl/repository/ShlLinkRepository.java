package com.chanakya.hsapi.shl.repository;

import com.chanakya.hsapi.shl.model.ShlLinkDocument;
import com.chanakya.hsapi.shl.model.ShlStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ShlLinkRepository extends MongoRepository<ShlLinkDocument, String> {

    List<ShlLinkDocument> findByEnterpriseIdAndStatus(String enterpriseId, ShlStatus status);

    List<ShlLinkDocument> findByEnterpriseId(String enterpriseId);
}
