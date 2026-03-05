package com.chanakya.hsapi.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Service;

@Service
public class FhirSerializationService {

    private final FhirContext fhirContext;

    public FhirSerializationService() {
        this.fhirContext = FhirContext.forR4();
    }

    public String toJson(Resource resource) {
        IParser parser = fhirContext.newJsonParser();
        parser.setPrettyPrint(false);
        return parser.encodeResourceToString(resource);
    }

    public <T extends Resource> T fromJson(String json, Class<T> resourceType) {
        IParser parser = fhirContext.newJsonParser();
        return parser.parseResource(resourceType, json);
    }

    public FhirContext getFhirContext() {
        return fhirContext;
    }
}
