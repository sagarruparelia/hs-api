package com.chanakya.hsapi.graphql;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.fhir.FhirClient;
import com.chanakya.hsapi.graphql.transform.AllergyTransform;
import com.chanakya.hsapi.graphql.type.AllergyType;
import com.chanakya.hsapi.shl.model.FhirResourceType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

import static com.chanakya.hsapi.graphql.MedicationController.filterAndSort;

@Controller
public class AllergyController {

    private final PatientCrosswalkService crosswalk;
    private final FhirClient fhirClient;
    private final AllergyTransform transform;
    private final AuditService auditService;
    private final HttpServletRequest request;

    public AllergyController(PatientCrosswalkService crosswalk, FhirClient fhirClient,
                              AllergyTransform transform, AuditService auditService,
                              HttpServletRequest request) {
        this.crosswalk = crosswalk;
        this.fhirClient = fhirClient;
        this.transform = transform;
        this.auditService = auditService;
        this.request = request;
    }

    @QueryMapping
    public List<AllergyType> allergies(@Argument String enterpriseId,
                                        @Argument String startDate,
                                        @Argument String endDate,
                                        @Argument String sortOrder) {
        String patientId = crosswalk.resolveHealthLakePatientId(enterpriseId);
        var bundle = fhirClient.searchResources("AllergyIntolerance", patientId);
        var results = transform.transform(bundle);
        results = filterAndSort(results, startDate, endDate, sortOrder, AllergyType::recordedDate);
        auditService.logFhirQuery(enterpriseId, FhirResourceType.AllergyIntolerance, request);
        return results;
    }
}
