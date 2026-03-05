package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.MedicationType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class MedicationTransform {

    public List<MedicationType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof MedicationRequest)
            .map(e -> (MedicationRequest) e.getResource())
            .map(this::toType)
            .toList();
    }

    private MedicationType toType(MedicationRequest mr) {
        String name = mr.hasMedicationCodeableConcept()
            ? mr.getMedicationCodeableConcept().getText()
            : (mr.hasMedicationReference() ? mr.getMedicationReference().getDisplay() : null);

        String dosage = mr.hasDosageInstruction() && !mr.getDosageInstruction().isEmpty()
            ? mr.getDosageInstruction().getFirst().getText()
            : null;

        String reason = mr.hasReasonCode() && !mr.getReasonCode().isEmpty()
            ? mr.getReasonCode().getFirst().getText()
            : null;

        LocalDate startDate = mr.hasAuthoredOn() ? toLocalDate(mr.getAuthoredOn()) : null;

        return new MedicationType(
            mr.getIdElement().getIdPart(),
            name,
            mr.hasStatus() ? mr.getStatus().toCode() : null,
            dosage,
            reason,
            startDate,
            null
        );
    }

    private LocalDate toLocalDate(java.util.Date date) {
        if (date == null) return null;
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }
}
