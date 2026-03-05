package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.CoverageType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coverage;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class CoverageTransform {

    public List<CoverageType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Coverage)
            .map(e -> (Coverage) e.getResource())
            .map(this::toType)
            .toList();
    }

    private CoverageType toType(Coverage c) {
        String type = c.hasType() ? c.getType().getText() : null;
        String payor = c.hasPayor() && !c.getPayor().isEmpty()
            ? c.getPayor().getFirst().getDisplay() : null;
        String subscriberId = c.hasSubscriberId() ? c.getSubscriberId() : null;
        LocalDate start = c.hasPeriod() && c.getPeriod().hasStart()
            ? c.getPeriod().getStart().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        LocalDate end = c.hasPeriod() && c.getPeriod().hasEnd()
            ? c.getPeriod().getEnd().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;

        return new CoverageType(
            c.getIdElement().getIdPart(),
            type,
            c.hasStatus() ? c.getStatus().toCode() : null,
            payor, subscriberId, start, end
        );
    }
}
