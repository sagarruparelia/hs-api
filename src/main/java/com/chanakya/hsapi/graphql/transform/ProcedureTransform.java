package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.ProcedureType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Procedure;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class ProcedureTransform {

    public List<ProcedureType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Procedure)
            .map(e -> (Procedure) e.getResource())
            .map(this::toType)
            .toList();
    }

    private ProcedureType toType(Procedure p) {
        String name = p.hasCode() ? p.getCode().getText() : null;
        LocalDate performed = p.hasPerformedDateTimeType() && p.getPerformedDateTimeType().getValue() != null
            ? p.getPerformedDateTimeType().getValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        String performer = p.hasPerformer() && !p.getPerformer().isEmpty()
            && p.getPerformer().getFirst().hasActor()
            ? p.getPerformer().getFirst().getActor().getDisplay() : null;
        String location = p.hasLocation() ? p.getLocation().getDisplay() : null;

        return new ProcedureType(
            p.getIdElement().getIdPart(),
            name,
            p.hasStatus() ? p.getStatus().toCode() : null,
            performed, performer, location
        );
    }
}
