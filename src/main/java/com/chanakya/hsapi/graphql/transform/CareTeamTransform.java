package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.CareTeamType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CareTeam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class CareTeamTransform {

    public List<CareTeamType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof CareTeam)
            .map(e -> (CareTeam) e.getResource())
            .map(this::toType)
            .toList();
    }

    private CareTeamType toType(CareTeam ct) {
        String category = ct.hasCategory() && !ct.getCategory().isEmpty()
            ? ct.getCategory().getFirst().getText() : null;
        List<String> participants = ct.hasParticipant()
            ? ct.getParticipant().stream()
                .filter(p -> p.hasMember())
                .map(p -> p.getMember().getDisplay())
                .toList()
            : List.of();
        LocalDate start = ct.hasPeriod() && ct.getPeriod().hasStart()
            ? ct.getPeriod().getStart().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;

        return new CareTeamType(
            ct.getIdElement().getIdPart(),
            ct.hasName() ? ct.getName() : null,
            ct.hasStatus() ? ct.getStatus().toCode() : null,
            category, participants, start
        );
    }
}
