package com.chanakya.hsapi.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class MongoConfig {

    private final MongoTemplate mongoTemplate;

    public MongoConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        // shl_links: {enterpriseId, status} compound index
        mongoTemplate.indexOps("shl_links")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .named("idx_enterpriseId_status"));

        // shl_links: {expiresAt} for TTL/expiry queries
        mongoTemplate.indexOps("shl_links")
            .ensureIndex(new Index()
                .on("expiresAt", Sort.Direction.ASC)
                .named("idx_expiresAt"));

        // shl_audit_log: {linkId, timestamp desc}
        mongoTemplate.indexOps("shl_audit_log")
            .ensureIndex(new Index()
                .on("linkId", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.DESC)
                .named("idx_linkId_timestamp"));

        // shl_audit_log: {enterpriseId, timestamp desc}
        mongoTemplate.indexOps("shl_audit_log")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.DESC)
                .named("idx_enterpriseId_timestamp"));

        // patient_crosswalk: {enterpriseId} unique
        mongoTemplate.indexOps("patient_crosswalk")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .unique()
                .named("idx_enterpriseId_unique"));

        // audit_log: {enterpriseId, timestamp desc}
        mongoTemplate.indexOps("audit_log")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.DESC)
                .named("idx_enterpriseId_timestamp"));
    }
}
