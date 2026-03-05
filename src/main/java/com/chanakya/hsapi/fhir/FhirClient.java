package com.chanakya.hsapi.fhir;

import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

@Service
public class FhirClient {

    private static final Logger log = LoggerFactory.getLogger(FhirClient.class);

    private final RestClient restClient;
    private final FhirSerializationService fhirSerialization;

    public FhirClient(@Value("${aws.healthlake.datastore-endpoint:}") String endpoint,
                      @Value("${aws.region}") String region,
                      DefaultCredentialsProvider credentialsProvider,
                      FhirSerializationService fhirSerialization) {
        this.fhirSerialization = fhirSerialization;

        RestClient.Builder builder = RestClient.builder();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.baseUrl(endpoint)
                .requestInterceptor(new SigV4RequestInterceptor(credentialsProvider, Region.of(region)));
        }
        this.restClient = builder.build();
    }

    public Bundle searchResources(String resourceType, String patientId) {
        String url = "/" + resourceType + "?patient=" + patientId + "&_count=100";
        log.debug("FHIR search: {}", url);

        String responseBody = restClient.get()
            .uri(url)
            .accept(MediaType.parseMediaType("application/fhir+json"))
            .retrieve()
            .body(String.class);

        return fhirSerialization.fromJson(responseBody, Bundle.class);
    }

    public Bundle searchResourcesBySubject(String resourceType, String patientId) {
        String url = "/" + resourceType + "?subject=" + patientId + "&_count=100";
        log.debug("FHIR search by subject: {}", url);

        String responseBody = restClient.get()
            .uri(url)
            .accept(MediaType.parseMediaType("application/fhir+json"))
            .retrieve()
            .body(String.class);

        return fhirSerialization.fromJson(responseBody, Bundle.class);
    }

    public Bundle getPatient(String patientId) {
        String url = "/Patient?_id=" + patientId;
        log.debug("FHIR get patient: {}", url);

        String responseBody = restClient.get()
            .uri(url)
            .accept(MediaType.parseMediaType("application/fhir+json"))
            .retrieve()
            .body(String.class);

        return fhirSerialization.fromJson(responseBody, Bundle.class);
    }

    public int countResources(String resourceType, String patientId) {
        String url = "/" + resourceType + "?patient=" + patientId + "&_summary=count";
        log.debug("FHIR count: {}", url);

        String responseBody = restClient.get()
            .uri(url)
            .accept(MediaType.parseMediaType("application/fhir+json"))
            .retrieve()
            .body(String.class);

        Bundle bundle = fhirSerialization.fromJson(responseBody, Bundle.class);
        return bundle.getTotal();
    }
}
