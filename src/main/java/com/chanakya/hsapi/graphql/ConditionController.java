package com.chanakya.hsapi.graphql;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.fhir.FhirClient;
import com.chanakya.hsapi.graphql.transform.ConditionTransform;
import com.chanakya.hsapi.graphql.type.ConditionType;
import com.chanakya.hsapi.shl.model.FhirResourceType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

import static com.chanakya.hsapi.graphql.MedicationController.filterAndSort;

@Controller
public class ConditionController {

    private final PatientCrosswalkService crosswalk;
    private final FhirClient fhirClient;
    private final ConditionTransform transform;
    private final AuditService auditService;
    private final HttpServletRequest request;

    public ConditionController(PatientCrosswalkService crosswalk, FhirClient fhirClient,
                                ConditionTransform transform, AuditService auditService,
                                HttpServletRequest request) {
        this.crosswalk = crosswalk;
        this.fhirClient = fhirClient;
        this.transform = transform;
        this.auditService = auditService;
        this.request = request;
    }

    @QueryMapping
    public List<ConditionType> conditions(@Argument String enterpriseId,
                                           @Argument String startDate,
                                           @Argument String endDate,
                                           @Argument String sortOrder) {
        String patientId = crosswalk.resolveHealthLakePatientId(enterpriseId);
        var bundle = fhirClient.searchResourcesBySubject("Condition", patientId);
        var results = transform.transform(bundle);
        results = filterAndSort(results, startDate, endDate, sortOrder, ConditionType::onsetDate);
        auditService.logFhirQuery(enterpriseId, FhirResourceType.Condition, request);
        return results;
    }
}
