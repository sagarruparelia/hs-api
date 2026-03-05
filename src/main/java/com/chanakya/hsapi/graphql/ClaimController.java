package com.chanakya.hsapi.graphql;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.fhir.FhirClient;
import com.chanakya.hsapi.graphql.transform.ClaimTransform;
import com.chanakya.hsapi.graphql.type.ClaimType;
import com.chanakya.hsapi.shl.model.FhirResourceType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

import static com.chanakya.hsapi.graphql.MedicationController.filterAndSort;

@Controller
public class ClaimController {

    private final PatientCrosswalkService crosswalk;
    private final FhirClient fhirClient;
    private final ClaimTransform transform;
    private final AuditService auditService;
    private final HttpServletRequest request;

    public ClaimController(PatientCrosswalkService crosswalk, FhirClient fhirClient,
                            ClaimTransform transform, AuditService auditService,
                            HttpServletRequest request) {
        this.crosswalk = crosswalk;
        this.fhirClient = fhirClient;
        this.transform = transform;
        this.auditService = auditService;
        this.request = request;
    }

    @QueryMapping
    public List<ClaimType> claims(@Argument String enterpriseId,
                                   @Argument String startDate,
                                   @Argument String endDate,
                                   @Argument String sortOrder) {
        String patientId = crosswalk.resolveHealthLakePatientId(enterpriseId);
        var bundle = fhirClient.searchResources("ExplanationOfBenefit", patientId);
        var results = transform.transform(bundle);
        results = filterAndSort(results, startDate, endDate, sortOrder, ClaimType::serviceDate);
        auditService.logFhirQuery(enterpriseId, FhirResourceType.CLAIM, request);
        return results;
    }
}
