package com.chanakya.hsapi.crypto;

import com.nimbusds.jose.JWEObject;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates JWE encryption output against SMART Health Links specification.
 *
 * Spec requires: alg=dir, enc=A256GCM, cty=application/fhir+json
 * Reference: https://hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/rkGeS5cIZe
 */
class JweComplianceTest {

    private final KeyGenerationService keyGen = new KeyGenerationService();
    private final EncryptionService encryption = new EncryptionService();

    private static final String SAMPLE_FHIR = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"timestamp\":\"2026-03-06T00:00:00Z\",\"entry\":[]}";

    @Test
    void jwe_usesDirectAlgorithm() throws Exception {
        String jwe = encrypt();
        JWEObject parsed = JWEObject.parse(jwe);
        assertEquals("dir", parsed.getHeader().getAlgorithm().getName(),
            "SHL spec requires alg=dir (direct encryption)");
    }

    @Test
    void jwe_usesA256GCM() throws Exception {
        String jwe = encrypt();
        JWEObject parsed = JWEObject.parse(jwe);
        assertEquals("A256GCM", parsed.getHeader().getEncryptionMethod().getName(),
            "SHL spec requires enc=A256GCM");
    }

    @Test
    void jwe_hasCorrectContentType() throws Exception {
        String jwe = encrypt();
        JWEObject parsed = JWEObject.parse(jwe);
        assertEquals("application/fhir+json", parsed.getHeader().getContentType(),
            "SHL spec requires cty=application/fhir+json");
    }

    @Test
    void jwe_usesDeflateCompression() throws Exception {
        String jwe = encrypt();
        JWEObject parsed = JWEObject.parse(jwe);
        assertEquals("DEF", parsed.getHeader().getCompressionAlgorithm().getName(),
            "Implementation uses DEF (DEFLATE) compression");
    }

    @Test
    void jwe_hasEmptyEncryptedKey() {
        String jwe = encrypt();
        String[] parts = jwe.split("\\.");
        assertEquals("", parts[1],
            "For dir algorithm, the encrypted key segment must be empty");
    }

    @Test
    void jwe_hasFiveSegments() {
        String jwe = encrypt();
        String[] parts = jwe.split("\\.");
        assertEquals(5, parts.length,
            "JWE compact serialization must have 5 dot-separated parts: header.encryptedKey.iv.ciphertext.tag");
    }

    @Test
    void jwe_roundTrip_preservesFhirContent() {
        String rawKey = keyGen.generateAesKeyBase64Url();
        String jwe = encryption.encryptToJwe(SAMPLE_FHIR, rawKey);
        String decrypted = encryption.decryptJwe(jwe, rawKey);
        assertEquals(SAMPLE_FHIR, decrypted,
            "Decrypted JWE must exactly match original FHIR Bundle JSON");
    }

    @Test
    void jwe_differentEncryptionsProduceDifferentOutput() {
        String rawKey = keyGen.generateAesKeyBase64Url();
        String jwe1 = encryption.encryptToJwe(SAMPLE_FHIR, rawKey);
        String jwe2 = encryption.encryptToJwe(SAMPLE_FHIR, rawKey);
        assertNotEquals(jwe1, jwe2,
            "Same plaintext + same key must produce different ciphertext (unique IV/nonce per encryption)");
    }

    private String encrypt() {
        String rawKey = keyGen.generateAesKeyBase64Url();
        return encryption.encryptToJwe(SAMPLE_FHIR, rawKey);
    }
}
