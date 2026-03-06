package com.chanakya.hsapi.graphql;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.crosswalk.PatientCrosswalkService;
import com.chanakya.hsapi.fhir.FhirClient;
import com.chanakya.hsapi.graphql.transform.MedicationTransform;
import com.chanakya.hsapi.graphql.type.MedicationType;
import com.chanakya.hsapi.shl.model.FhirResourceType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Controller
public class MedicationController {

    private final PatientCrosswalkService crosswalk;
    private final FhirClient fhirClient;
    private final MedicationTransform transform;
    private final AuditService auditService;
    private final HttpServletRequest request;

    public MedicationController(PatientCrosswalkService crosswalk, FhirClient fhirClient,
                                MedicationTransform transform, AuditService auditService,
                                HttpServletRequest request) {
        this.crosswalk = crosswalk;
        this.fhirClient = fhirClient;
        this.transform = transform;
        this.auditService = auditService;
        this.request = request;
    }

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

    static <T> List<T> filterAndSort(List<T> items, String startDate, String endDate,
                                     String sortOrder,
                                     java.util.function.Function<T, LocalDate> dateExtractor) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : null;
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : null;

        var stream = items.stream();
        if (start != null) {
            stream = stream.filter(i -> {
                LocalDate d = dateExtractor.apply(i);
                return d != null && !d.isBefore(start);
            });
        }
        if (end != null) {
            stream = stream.filter(i -> {
                LocalDate d = dateExtractor.apply(i);
                return d != null && !d.isAfter(end);
            });
        }

        Comparator<T> comp = Comparator.comparing(dateExtractor,
            Comparator.nullsLast(Comparator.naturalOrder()));
        if (!"ASC".equalsIgnoreCase(sortOrder)) {
            comp = comp.reversed();
        }

        return stream.sorted(comp).toList();
    }
}
