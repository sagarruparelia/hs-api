package com.chanakya.hsapi.crosswalk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@RequiredArgsConstructor
@Service
public class PatientCrosswalkService {

    private final PatientCrosswalkRepository repository;

    public String resolveHealthLakePatientId(String enterpriseId) {
        return repository.findByEnterpriseId(enterpriseId)
            .map(PatientCrosswalkDocument::getHealthLakePatientId)
            .orElseThrow(() -> new NoSuchElementException(
                "No patient crosswalk found for enterpriseId: " + enterpriseId));
    }
}
