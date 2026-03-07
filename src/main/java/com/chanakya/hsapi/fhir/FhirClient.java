package com.chanakya.hsapi.fhir;

import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class FhirClient {

    private static final int HTTP_OK = 200;
    private static final int FHIR_DEFAULT_PAGE_SIZE = 100;

    private final FhirSerializationService fhirSerialization;
    private final SdkHttpClient httpClient;
    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;
    private final String baseEndpoint;

    public FhirClient(@Value("${aws.healthlake.datastore-endpoint:}") String endpoint,
                      @Value("${aws.region}") String regionStr,
                      AwsCredentialsProvider credentialsProvider,
                      FhirSerializationService fhirSerialization) {
        this.fhirSerialization = fhirSerialization;
        this.credentialsProvider = credentialsProvider;
        this.region = Region.of(regionStr);
        this.baseEndpoint = (endpoint != null && endpoint.endsWith("/"))
            ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.httpClient = Apache5HttpClient.builder().build();
    }

    private String executeSignedGet(String path) {
        String fullUrl = baseEndpoint + path;
        log.debug("HealthLake GET {}", fullUrl);

        SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
            .uri(URI.create(fullUrl))
            .method(SdkHttpMethod.GET)
            .putHeader("Accept", "application/fhir+json")
            .build();

        Aws4SignerParams params = Aws4SignerParams.builder()
            .awsCredentials(credentialsProvider.resolveCredentials())
            .signingName("healthlake")
            .signingRegion(region)
            .build();

        SdkHttpFullRequest signed = Aws4Signer.create().sign(unsigned, params);

        HttpExecuteRequest executeReq = HttpExecuteRequest.builder()
            .request(signed)
            .build();

        try {
            HttpExecuteResponse response = httpClient.prepareRequest(executeReq).call();
            int status = response.httpResponse().statusCode();
            byte[] bodyBytes = response.responseBody()
                .orElseThrow(() -> new IOException("No response body"))
                .readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            if (status != HTTP_OK) {
                log.error("HealthLake error: status={}, body={}", status, body);
                throw new RuntimeException("HealthLake request failed with status " + status);
            }

            return body;
        } catch (IOException e) {
            throw new UncheckedIOException("HealthLake request failed", e);
        }
    }

    public Bundle searchResources(String resourceType, String patientId) {
        String path = "/" + resourceType + "?patient=" + patientId + "&_count=" + FHIR_DEFAULT_PAGE_SIZE;
        return fhirSerialization.fromJson(executeSignedGet(path), Bundle.class);
    }

    public Bundle searchResourcesBySubject(String resourceType, String patientId) {
        String path = "/" + resourceType + "?subject=" + patientId + "&_count=" + FHIR_DEFAULT_PAGE_SIZE;
        return fhirSerialization.fromJson(executeSignedGet(path), Bundle.class);
    }

    public Bundle getPatient(String patientId) {
        String path = "/Patient?_id=" + patientId;
        return fhirSerialization.fromJson(executeSignedGet(path), Bundle.class);
    }

    public int countResources(String resourceType, String patientId) {
        String path = "/" + resourceType + "?patient=" + patientId + "&_summary=count";
        Bundle bundle = fhirSerialization.fromJson(executeSignedGet(path), Bundle.class);
        return bundle.getTotal();
    }
}
