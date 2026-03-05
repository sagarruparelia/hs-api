package com.chanakya.hsapi.crosswalk;

import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
public class PatientCrosswalkService {

    private final PatientCrosswalkRepository repository;

    public PatientCrosswalkService(PatientCrosswalkRepository repository) {
        this.repository = repository;
    }

    public String resolveHealthLakePatientId(String enterpriseId) {
        return repository.findByEnterpriseId(enterpriseId)
            .map(PatientCrosswalkDocument::getHealthLakePatientId)
            .orElseThrow(() -> new NoSuchElementException(
                "No patient crosswalk found for enterpriseId: " + enterpriseId));
    }
}
