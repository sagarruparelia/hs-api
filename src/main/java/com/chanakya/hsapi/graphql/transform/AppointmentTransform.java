package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.AppointmentType;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class AppointmentTransform {

    public List<AppointmentType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Appointment)
            .map(e -> (Appointment) e.getResource())
            .map(this::toType)
            .toList();
    }

    private AppointmentType toType(Appointment a) {
        String type = a.hasAppointmentType() ? a.getAppointmentType().getText() : null;
        LocalDate date = a.hasStart()
            ? a.getStart().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        String participant = a.hasParticipant() && !a.getParticipant().isEmpty()
            && a.getParticipant().getFirst().hasActor()
            ? a.getParticipant().getFirst().getActor().getDisplay() : null;

        return new AppointmentType(
            a.getIdElement().getIdPart(),
            a.hasStatus() ? a.getStatus().toCode() : null,
            type,
            a.hasDescription() ? a.getDescription() : null,
            date, participant, null
        );
    }
}
