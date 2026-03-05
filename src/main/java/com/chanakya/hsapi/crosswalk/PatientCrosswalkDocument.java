package com.chanakya.hsapi.crosswalk;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("patient_crosswalk")
public class PatientCrosswalkDocument {

    @Id
    private String id;
    private String enterpriseId;
    private String healthLakePatientId;

    public PatientCrosswalkDocument() {}

    public PatientCrosswalkDocument(String enterpriseId, String healthLakePatientId) {
        this.enterpriseId = enterpriseId;
        this.healthLakePatientId = healthLakePatientId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
    public String getHealthLakePatientId() { return healthLakePatientId; }
    public void setHealthLakePatientId(String healthLakePatientId) { this.healthLakePatientId = healthLakePatientId; }
}
