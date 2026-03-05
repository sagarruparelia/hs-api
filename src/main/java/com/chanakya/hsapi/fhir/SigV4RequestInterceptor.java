package com.chanakya.hsapi.fhir;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.net.URI;

public class SigV4RequestInterceptor implements ClientHttpRequestInterceptor {

    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;

    public SigV4RequestInterceptor(AwsCredentialsProvider credentialsProvider, Region region) {
        this.credentialsProvider = credentialsProvider;
        this.region = region;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        try {
            URI uri = request.getURI();
            SdkHttpFullRequest.Builder sdkRequestBuilder = SdkHttpFullRequest.builder()
                .uri(uri)
                .method(SdkHttpMethod.fromValue(request.getMethod().name()));

            request.getHeaders().forEach((name, values) ->
                values.forEach(value -> sdkRequestBuilder.putHeader(name, value)));

            if (body != null && body.length > 0) {
                sdkRequestBuilder.contentStreamProvider(() -> new java.io.ByteArrayInputStream(body));
            }

            Aws4Signer signer = Aws4Signer.create();
            Aws4SignerParams signerParams = Aws4SignerParams.builder()
                .awsCredentials(credentialsProvider.resolveCredentials())
                .signingName("healthlake")
                .signingRegion(region)
                .build();

            SdkHttpFullRequest signedRequest = signer.sign(sdkRequestBuilder.build(), signerParams);

            signedRequest.headers().forEach((name, values) ->
                values.forEach(value -> request.getHeaders().set(name, value)));

            return execution.execute(request, body);
        } catch (Exception e) {
            throw new IOException("SigV4 signing failed", e);
        }
    }
}
