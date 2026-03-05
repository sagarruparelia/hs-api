package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.ConditionType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class ConditionTransform {

    public List<ConditionType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Condition)
            .map(e -> (Condition) e.getResource())
            .map(this::toType)
            .toList();
    }

    private ConditionType toType(Condition c) {
        String name = c.hasCode() ? c.getCode().getText() : null;
        String status = c.hasClinicalStatus() ? c.getClinicalStatus().getText() : null;
        String category = c.hasCategory() && !c.getCategory().isEmpty()
            ? c.getCategory().getFirst().getText() : null;
        LocalDate onset = c.hasOnsetDateTimeType() && c.getOnsetDateTimeType().getValue() != null
            ? c.getOnsetDateTimeType().getValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        LocalDate abatement = c.hasAbatementDateTimeType() && c.getAbatementDateTimeType().getValue() != null
            ? c.getAbatementDateTimeType().getValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;

        return new ConditionType(
            c.getIdElement().getIdPart(),
            name, status, category, onset, abatement
        );
    }
}
