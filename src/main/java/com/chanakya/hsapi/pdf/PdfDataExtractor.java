package com.chanakya.hsapi.pdf;

import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class PdfDataExtractor {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private static final Map<String, String> RESOURCE_TYPE_LABELS = Map.ofEntries(
        Map.entry("Patient", "Patient Information"),
        Map.entry("MedicationRequest", "Medications"),
        Map.entry("Condition", "Conditions & Diagnoses"),
        Map.entry("AllergyIntolerance", "Allergies"),
        Map.entry("Immunization", "Immunizations"),
        Map.entry("Observation", "Lab Results & Vitals"),
        Map.entry("Procedure", "Procedures"),
        Map.entry("Coverage", "Insurance Coverage"),
        Map.entry("ExplanationOfBenefit", "Claims & Benefits"),
        Map.entry("Appointment", "Appointments"),
        Map.entry("CareTeam", "Care Team")
    );

    public record PatientInfo(String name, String birthDate, String gender) {}
    public record ResourceLine(String summary) {}
    public record ResourceSection(String title, int count, List<ResourceLine> lines) {}
    public record PdfData(PatientInfo patientInfo, List<ResourceSection> sections) {}

    public PdfData extract(Bundle bundle, String patientName) {
        if (bundle == null || !bundle.hasEntry()) {
            return new PdfData(new PatientInfo(patientName, null, null), List.of());
        }

        Map<String, List<Resource>> grouped = new LinkedHashMap<>();
        PatientInfo patientInfo = null;

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            if (resource == null) continue;

            String type = resource.fhirType();

            if (resource instanceof Patient p) {
                patientInfo = extractPatientInfo(p, patientName);
            } else if (resource instanceof DocumentReference) {
                // Skip PDF DocumentReference — don't render it in the PDF itself
                continue;
            } else {
                grouped.computeIfAbsent(type, _ -> new ArrayList<>()).add(resource);
            }
        }

        if (patientInfo == null) {
            patientInfo = new PatientInfo(patientName, null, null);
        }

        List<ResourceSection> sections = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String type = entry.getKey();
            List<Resource> resources = entry.getValue();
            String label = RESOURCE_TYPE_LABELS.getOrDefault(type, type);
            List<ResourceLine> lines = resources.stream()
                .map(r -> new ResourceLine(summarize(type, r)))
                .filter(line -> !line.summary().isBlank())
                .toList();
            sections.add(new ResourceSection(label, resources.size(), lines));
        }

        return new PdfData(patientInfo, sections);
    }

    private PatientInfo extractPatientInfo(Patient p, String fallbackName) {
        String name = fallbackName;
        if (p.hasName() && !p.getName().isEmpty()) {
            HumanName hn = p.getName().getFirst();
            String given = hn.hasGiven() && !hn.getGiven().isEmpty()
                ? hn.getGiven().stream().map(StringType::getValue).filter(Objects::nonNull).reduce((a, b) -> a + " " + b).orElse("")
                : "";
            String family = hn.hasFamily() ? hn.getFamily() : "";
            String fullName = (given + " " + family).trim();
            if (!fullName.isEmpty()) name = fullName;
        }

        String birthDate = null;
        if (p.hasBirthDate()) {
            birthDate = formatDate(p.getBirthDate());
        }

        String gender = null;
        if (p.hasGender()) {
            gender = capitalize(p.getGender().toCode());
        }

        return new PatientInfo(name, birthDate, gender);
    }

    private String summarize(String type, Resource resource) {
        return switch (type) {
            case "MedicationRequest" -> summarizeMedication((MedicationRequest) resource);
            case "Condition" -> summarizeCondition((Condition) resource);
            case "AllergyIntolerance" -> summarizeAllergy((AllergyIntolerance) resource);
            case "Immunization" -> summarizeImmunization((Immunization) resource);
            case "Observation" -> summarizeObservation((Observation) resource);
            case "Procedure" -> summarizeProcedure((Procedure) resource);
            case "Coverage" -> summarizeCoverage((Coverage) resource);
            case "ExplanationOfBenefit" -> summarizeEob((ExplanationOfBenefit) resource);
            case "Appointment" -> summarizeAppointment((Appointment) resource);
            case "CareTeam" -> summarizeCareTeam((CareTeam) resource);
            default -> extractCodeText(resource);
        };
    }

    private String summarizeMedication(MedicationRequest mr) {
        String med = "Unknown Medication";
        if (mr.hasMedicationCodeableConcept()) {
            med = mr.getMedicationCodeableConcept().getText();
        } else if (mr.hasMedicationReference()) {
            med = mr.getMedicationReference().getDisplay();
        }
        if (med == null) med = "Unknown Medication";

        String status = mr.hasStatus() ? mr.getStatus().toCode() : null;
        String dosage = null;
        if (mr.hasDosageInstruction() && !mr.getDosageInstruction().isEmpty()) {
            dosage = mr.getDosageInstruction().getFirst().getText();
        }

        var sb = new StringBuilder(med);
        if (status != null) sb.append(" (").append(status).append(")");
        if (dosage != null) sb.append(" - ").append(dosage);
        return sb.toString();
    }

    private String summarizeCondition(Condition c) {
        String name = c.hasCode() ? c.getCode().getText() : null;
        if (name == null) name = extractCodeText(c);

        String onset = null;
        if (c.hasOnsetDateTimeType() && c.getOnsetDateTimeType().getValue() != null) {
            onset = formatDate(c.getOnsetDateTimeType().getValue());
        }

        return name + (onset != null ? " - onset: " + onset : "");
    }

    private String summarizeAllergy(AllergyIntolerance ai) {
        String substance = ai.hasCode() ? ai.getCode().getText() : null;
        if (substance == null) substance = extractCodeText(ai);

        String reaction = null;
        if (ai.hasReaction() && !ai.getReaction().isEmpty()) {
            var r = ai.getReaction().getFirst();
            if (r.hasManifestation() && !r.getManifestation().isEmpty()) {
                reaction = r.getManifestation().getFirst().getText();
            }
        }

        String severity = null;
        if (ai.hasReaction() && !ai.getReaction().isEmpty()) {
            var r = ai.getReaction().getFirst();
            if (r.hasSeverity()) {
                severity = r.getSeverity().toCode();
            }
        }

        var sb = new StringBuilder(substance);
        if (reaction != null) sb.append(" - ").append(reaction);
        if (severity != null) sb.append(" (").append(severity).append(")");
        return sb.toString();
    }

    private String summarizeImmunization(Immunization imm) {
        String vaccine = imm.hasVaccineCode() ? imm.getVaccineCode().getText() : null;
        if (vaccine == null) vaccine = "Immunization";

        String date = null;
        if (imm.hasOccurrenceDateTimeType() && imm.getOccurrenceDateTimeType().getValue() != null) {
            date = formatDate(imm.getOccurrenceDateTimeType().getValue());
        }

        return vaccine + (date != null ? " - " + date : "");
    }

    private String summarizeObservation(Observation obs) {
        String test = obs.hasCode() ? obs.getCode().getText() : null;
        if (test == null) test = extractCodeText(obs);

        String value = null;
        if (obs.hasValueQuantity()) {
            var vq = obs.getValueQuantity();
            if (vq.hasValue()) {
                value = vq.getValue().toPlainString();
                if (vq.hasUnit()) value += " " + vq.getUnit();
            }
        } else if (obs.hasValueStringType()) {
            value = obs.getValueStringType().getValue();
        }

        String date = null;
        if (obs.hasEffectiveDateTimeType() && obs.getEffectiveDateTimeType().getValue() != null) {
            date = formatDate(obs.getEffectiveDateTimeType().getValue());
        }

        var sb = new StringBuilder(test);
        if (value != null) sb.append(": ").append(value);
        if (date != null) sb.append(" - ").append(date);
        return sb.toString();
    }

    private String summarizeProcedure(Procedure p) {
        String name = p.hasCode() ? p.getCode().getText() : null;
        if (name == null) name = extractCodeText(p);

        String date = null;
        if (p.hasPerformedDateTimeType() && p.getPerformedDateTimeType().getValue() != null) {
            date = formatDate(p.getPerformedDateTimeType().getValue());
        }

        return name + (date != null ? " - " + date : "");
    }

    private String summarizeCoverage(Coverage c) {
        String plan = c.hasType() ? c.getType().getText() : "Coverage";
        if (plan == null) plan = "Coverage";

        String status = c.hasStatus() ? c.getStatus().toCode() : null;

        return plan + (status != null ? " [" + status + "]" : "");
    }

    private String summarizeEob(ExplanationOfBenefit eob) {
        String type = eob.hasType() ? eob.getType().getText() : "Claim";
        if (type == null) type = "Claim";

        String date = null;
        if (eob.hasCreated()) {
            date = formatDate(eob.getCreated());
        }

        return type + (date != null ? " (" + date + ")" : "");
    }

    private String summarizeAppointment(Appointment a) {
        String desc = a.hasDescription() ? a.getDescription() : "Appointment";

        String date = null;
        if (a.hasStart()) {
            date = formatDate(a.getStart());
        }

        return desc + (date != null ? " - " + date : "");
    }

    private String summarizeCareTeam(CareTeam ct) {
        String name = ct.hasName() ? ct.getName() : "Care Team";

        int count = 0;
        if (ct.hasParticipant()) {
            count = ct.getParticipant().size();
        }

        return name + " (" + count + " member" + (count != 1 ? "s" : "") + ")";
    }

    private String extractCodeText(Resource resource) {
        if (resource instanceof DomainResource dr) {
            // Try to get code from common FHIR pattern via reflection-free approach
            try {
                var method = resource.getClass().getMethod("getCode");
                Object result = method.invoke(resource);
                if (result instanceof CodeableConcept cc && cc.hasText()) {
                    return cc.getText();
                }
                if (result instanceof CodeableConcept cc && cc.hasCoding()) {
                    var coding = cc.getCodingFirstRep();
                    return coding.hasDisplay() ? coding.getDisplay() : coding.getCode();
                }
            } catch (Exception ignored) {
                // No getCode() method — fall back to resource type
            }
        }
        return resource.fhirType();
    }

    private String formatDate(Date date) {
        if (date == null) return null;
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FMT);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
