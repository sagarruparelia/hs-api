package com.chanakya.hsapi.crosswalk;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@NoArgsConstructor
@Document("patient_crosswalk")
public class PatientCrosswalkDocument {

    @Id
    private String id;
    private String enterpriseId;
    private String healthLakePatientId;

    public PatientCrosswalkDocument(String enterpriseId, String healthLakePatientId) {
        this.enterpriseId = enterpriseId;
        this.healthLakePatientId = healthLakePatientId;
    }
}
