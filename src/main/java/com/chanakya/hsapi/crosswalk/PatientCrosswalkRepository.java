package com.chanakya.hsapi.crosswalk;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PatientCrosswalkRepository extends MongoRepository<PatientCrosswalkDocument, String> {

    Optional<PatientCrosswalkDocument> findByEnterpriseId(String enterpriseId);
}
