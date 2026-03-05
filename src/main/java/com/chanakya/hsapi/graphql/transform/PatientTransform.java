package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.PatientSummaryType;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class PatientTransform {

    public PatientSummaryType transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null || bundle.getEntry().isEmpty()) return null;
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof Patient)
            .map(e -> (Patient) e.getResource())
            .findFirst()
            .map(this::toType)
            .orElse(null);
    }

    private PatientSummaryType toType(Patient p) {
        String firstName = null;
        String lastName = null;
        if (p.hasName() && !p.getName().isEmpty()) {
            HumanName name = p.getName().getFirst();
            firstName = name.hasGiven() && !name.getGiven().isEmpty()
                ? name.getGiven().getFirst().getValue() : null;
            lastName = name.hasFamily() ? name.getFamily() : null;
        }

        LocalDate birthDate = p.hasBirthDate()
            ? p.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;

        String address = null;
        if (p.hasAddress() && !p.getAddress().isEmpty()) {
            Address addr = p.getAddress().getFirst();
            address = addr.hasText() ? addr.getText() : String.join(", ",
                addr.hasLine() ? addr.getLine().stream().map(StringType::getValue).toList() : java.util.List.of());
        }

        String phone = null;
        String email = null;
        if (p.hasTelecom()) {
            for (ContactPoint cp : p.getTelecom()) {
                if (cp.hasSystem() && cp.getSystem() == ContactPoint.ContactPointSystem.PHONE && phone == null) {
                    phone = cp.getValue();
                }
                if (cp.hasSystem() && cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL && email == null) {
                    email = cp.getValue();
                }
            }
        }

        return new PatientSummaryType(
            p.getIdElement().getIdPart(),
            firstName, lastName, birthDate,
            p.hasGender() ? p.getGender().toCode() : null,
            address, phone, email
        );
    }
}
