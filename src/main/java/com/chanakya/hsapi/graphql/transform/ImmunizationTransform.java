package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.ImmunizationType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Immunization;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class ImmunizationTransform {

    public List<ImmunizationType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Immunization)
            .map(e -> (Immunization) e.getResource())
            .map(this::toType)
            .toList();
    }

    private ImmunizationType toType(Immunization imm) {
        String name = imm.hasVaccineCode() ? imm.getVaccineCode().getText() : null;
        LocalDate date = imm.hasOccurrenceDateTimeType() && imm.getOccurrenceDateTimeType().getValue() != null
            ? imm.getOccurrenceDateTimeType().getValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            : null;
        String site = imm.hasSite() ? imm.getSite().getText() : null;
        String performer = imm.hasPerformer() && !imm.getPerformer().isEmpty()
            && imm.getPerformer().getFirst().hasActor()
            ? imm.getPerformer().getFirst().getActor().getDisplay() : null;

        return new ImmunizationType(
            imm.getIdElement().getIdPart(),
            name,
            imm.hasStatus() ? imm.getStatus().toCode() : null,
            date, site, performer
        );
    }
}
