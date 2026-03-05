package com.chanakya.hsapi.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class MongoConfig {

    @Bean
    InitializingBean disableMongoClassMapping(MappingMongoConverter converter) {
        return () -> converter.setTypeMapper(new DefaultMongoTypeMapper(null));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes(ApplicationReadyEvent event) {
        MongoTemplate mongoTemplate = event.getApplicationContext().getBean(MongoTemplate.class);

        mongoTemplate.indexOps("shl_links")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .named("idx_enterpriseId_status"));

        mongoTemplate.indexOps("shl_links")
            .ensureIndex(new Index()
                .on("expiresAt", Sort.Direction.ASC)
                .named("idx_expiresAt"));

        mongoTemplate.indexOps("shl_audit_log")
            .ensureIndex(new Index()
                .on("linkId", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.DESC)
                .named("idx_linkId_timestamp"));

        mongoTemplate.indexOps("shl_audit_log")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.DESC)
                .named("idx_enterpriseId_timestamp"));

        mongoTemplate.indexOps("patient_crosswalk")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .unique()
                .named("idx_enterpriseId_unique"));

        mongoTemplate.indexOps("audit_log")
            .ensureIndex(new Index()
                .on("enterpriseId", Sort.Direction.ASC)
                .on("timestamp", Sort.Direction.DESC)
                .named("idx_enterpriseId_timestamp"));
    }
}
