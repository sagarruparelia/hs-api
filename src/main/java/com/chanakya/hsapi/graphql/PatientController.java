package com.chanakya.hsapi.graphql;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.fhir.FhirClient;
import com.chanakya.hsapi.graphql.transform.PatientTransform;
import com.chanakya.hsapi.graphql.type.PatientSummaryType;
import com.chanakya.hsapi.shl.model.FhirResourceType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@RequiredArgsConstructor
@Controller
public class PatientController {

    private final PatientCrosswalkService crosswalk;
    private final FhirClient fhirClient;
    private final PatientTransform transform;
    private final AuditService auditService;
    private final HttpServletRequest request;

    @QueryMapping
    public PatientSummaryType patientSummary(@Argument String enterpriseId) {
        String patientId = crosswalk.resolveHealthLakePatientId(enterpriseId);
        var bundle = fhirClient.getPatient(patientId);
        auditService.logFhirQuery(enterpriseId, FhirResourceType.Patient, request);
        return transform.transform(bundle);
    }
}
