package com.chanakya.hsapi.graphql.transform;

import com.chanakya.hsapi.graphql.type.ClaimType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class ClaimTransform {

    public List<ClaimType> transform(Bundle bundle) {
        if (bundle == null || bundle.getEntry() == null) return List.of();
        return bundle.getEntry().stream()
            .filter(e -> e.getResource() instanceof ExplanationOfBenefit)
            .map(e -> (ExplanationOfBenefit) e.getResource())
            .map(this::toType)
            .toList();
    }

    private ClaimType toType(ExplanationOfBenefit eob) {
        String type = eob.hasType() ? eob.getType().getText() : null;
        String provider = eob.hasProvider() ? eob.getProvider().getDisplay() : null;
        LocalDate serviceDate = eob.hasBillablePeriod() && eob.getBillablePeriod().hasStart()
            ? eob.getBillablePeriod().getStart().toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        String totalAmount = eob.hasTotal() && !eob.getTotal().isEmpty()
            && eob.getTotal().getFirst().hasAmount()
            ? eob.getTotal().getFirst().getAmount().getValue().toPlainString() : null;
        String currency = eob.hasTotal() && !eob.getTotal().isEmpty()
            && eob.getTotal().getFirst().hasAmount()
            ? eob.getTotal().getFirst().getAmount().getCurrency() : null;

        return new ClaimType(
            eob.getIdElement().getIdPart(),
            type,
            eob.hasStatus() ? eob.getStatus().toCode() : null,
            provider, serviceDate, totalAmount, currency
        );
    }
}
