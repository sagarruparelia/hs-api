package com.chanakya.hsapi.graphql;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.fhir.FhirClient;
import com.chanakya.hsapi.graphql.transform.MedicationTransform;
import com.chanakya.hsapi.graphql.type.MedicationType;
import com.chanakya.hsapi.shl.model.FhirResourceType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

import static com.chanakya.hsapi.graphql.GraphQlUtils.filterAndSort;

@RequiredArgsConstructor
@Controller
public class MedicationController {

    private final PatientCrosswalkService crosswalk;
    private final FhirClient fhirClient;
    private final MedicationTransform transform;
    private final AuditService auditService;
    private final HttpServletRequest request;

    @QueryMapping
    public List<MedicationType> medications(@Argument String enterpriseId,
                                            @Argument String startDate,
                                            @Argument String endDate,
                                            @Argument String sortOrder) {
        String patientId = crosswalk.resolveHealthLakePatientId(enterpriseId);
        var bundle = fhirClient.searchResources("MedicationRequest", patientId);
        var results = transform.transform(bundle);
        results = filterAndSort(results, startDate, endDate, sortOrder, MedicationType::startDate);
        auditService.logFhirQuery(enterpriseId, FhirResourceType.MedicationRequest, request);
        return results;
    }
}
