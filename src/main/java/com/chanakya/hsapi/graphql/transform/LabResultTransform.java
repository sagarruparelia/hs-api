package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.LabResultType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class LabResultTransform {

    public List<LabResultType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Observation)
            .map(e -> (Observation) e.getResource())
            .map(this::toType)
            .toList();
    }

    private LabResultType toType(Observation obs) {
        String name = obs.hasCode() ? obs.getCode().getText() : null;
        String value = null;
        String unit = null;
        if (obs.hasValueQuantity()) {
            Quantity q = obs.getValueQuantity();
            value = q.hasValue() ? q.getValue().toPlainString() : null;
            unit = q.hasUnit() ? q.getUnit() : null;
        } else if (obs.hasValueStringType()) {
            value = obs.getValueStringType().getValue();
        }
        LocalDate effectiveDate = obs.hasEffectiveDateTimeType() && obs.getEffectiveDateTimeType().getValue() != null
            ? obs.getEffectiveDateTimeType().getValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        String refRange = obs.hasReferenceRange() && !obs.getReferenceRange().isEmpty()
            ? obs.getReferenceRange().getFirst().getText() : null;
        String interpretation = obs.hasInterpretation() && !obs.getInterpretation().isEmpty()
            ? obs.getInterpretation().getFirst().getText() : null;

        return new LabResultType(
            obs.getIdElement().getIdPart(),
            name, value, unit,
            obs.hasStatus() ? obs.getStatus().toCode() : null,
            effectiveDate, refRange, interpretation
        );
    }
}
