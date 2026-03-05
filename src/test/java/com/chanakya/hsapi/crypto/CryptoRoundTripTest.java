package com.chanakya.hsapi.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CryptoRoundTripTest {

    private final KeyGenerationService keyGen = new KeyGenerationService();
    private final EncryptionService encryption = new EncryptionService();
    private final FieldEncryptionService fieldEncryption = new FieldEncryptionService("test-encryption-key-32bytes-lon!");

    @Test
    void fullRoundTrip_keyGen_fieldEncrypt_fieldDecrypt_jweEncrypt_jweDecrypt() {
        // 1. Generate AES key
        String rawKey = keyGen.generateAesKeyBase64Url();
        assertNotNull(rawKey);
        byte[] keyBytes = Base64.getUrlDecoder().decode(rawKey);
        assertEquals(32, keyBytes.length, "AES key must be 32 bytes");

        // 2. Field-encrypt the key (simulating MongoDB storage)
        String encryptedKey = fieldEncryption.encrypt(rawKey);
        assertNotEquals(rawKey, encryptedKey, "Encrypted key must differ from raw key");

        // 3. Field-decrypt the key (simulating retrieval from MongoDB)
        String decryptedKey = fieldEncryption.decrypt(encryptedKey);
        assertEquals(rawKey, decryptedKey, "Decrypted key must match original");

        // 4. JWE encrypt content with the raw key
        String fhirBundle = """
            {"resourceType":"Bundle","type":"collection","entry":[]}""";
        String jwe = encryption.encryptToJwe(fhirBundle, rawKey);
        assertNotNull(jwe);
        assertTrue(jwe.contains("."), "JWE must have dot-separated segments");

        // 5. JWE decrypt content
        String decrypted = encryption.decryptJwe(jwe, rawKey);
        assertEquals(fhirBundle, decrypted, "Decrypted JWE must match original FHIR Bundle");
    }

    @Test
    void fieldEncryption_encryptedValueIsNotPlaintext() {
        String secret = "my-secret-aes-key-base64url-encoded";
        String encrypted = fieldEncryption.encrypt(secret);
        assertFalse(encrypted.contains(secret), "Encrypted value must not contain plaintext");
    }

    @Test
    void linkId_has256BitEntropy() {
        String linkId = keyGen.generateLinkId();
        byte[] decoded = Base64.getUrlDecoder().decode(linkId);
        assertEquals(32, decoded.length, "Link ID must be 32 bytes (256-bit entropy)");
        assertEquals(43, linkId.length(), "Base64url of 32 bytes should be 43 chars");
    }
}
