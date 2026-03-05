package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.AllergyType;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class AllergyTransform {

    public List<AllergyType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof AllergyIntolerance)
            .map(e -> (AllergyIntolerance) e.getResource())
            .map(this::toType)
            .toList();
    }

    private AllergyType toType(AllergyIntolerance ai) {
        String substance = ai.hasCode() ? ai.getCode().getText() : null;
        String category = ai.hasCategory() && !ai.getCategory().isEmpty()
            ? ai.getCategory().getFirst().getValue().toCode() : null;
        String criticality = ai.hasCriticality() ? ai.getCriticality().toCode() : null;
        LocalDate recorded = ai.hasRecordedDate()
            ? ai.getRecordedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        String reaction = ai.hasReaction() && !ai.getReaction().isEmpty()
            && ai.getReaction().getFirst().hasManifestation()
            ? ai.getReaction().getFirst().getManifestation().getFirst().getText() : null;

        return new AllergyType(
            ai.getIdElement().getIdPart(),
            substance, category, criticality,
            ai.hasClinicalStatus() ? ai.getClinicalStatus().getText() : null,
            recorded, reaction
        );
    }
}
