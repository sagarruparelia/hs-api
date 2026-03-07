package com.chanakya.hsapi.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

@Slf4j
@RequiredArgsConstructor
@Service
public class S3PayloadService {

    private final S3Client s3Client;
    @Value("${aws.s3.bucket:}")
    private final String bucket;

    public String buildS3Key(String enterpriseId, String linkId) {
        return "shl/" + enterpriseId + "/" + linkId + ".jwe";
    }

    public void uploadJwe(String s3Key, String jweContent) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .contentType("application/jose")
                .build(),
            RequestBody.fromString(jweContent, StandardCharsets.UTF_8));
        log.debug("Uploaded JWE to s3://{}/{}", bucket, s3Key);
    }

    public String downloadJwe(String s3Key) {
        var response = s3Client.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build());
        return response.asString(StandardCharsets.UTF_8);
    }

    public void deleteJwe(String s3Key) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build());
        log.debug("Deleted JWE from s3://{}/{}", bucket, s3Key);
    }
}
