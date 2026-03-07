package com.chanakya.hsapi.crypto;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Service
public class EncryptionService {

    public String encryptToJwe(String plaintext, byte[] aesKey) {
        try {
            SecretKey key = new SecretKeySpec(aesKey, "AES");
            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                .contentType("application/fhir+json")
                .compressionAlgorithm(CompressionAlgorithm.DEF)
                .build();

            JWEObject jwe = new JWEObject(header, new Payload(plaintext));
            jwe.encrypt(new DirectEncrypter(key));
            return jwe.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("JWE encryption failed", e);
        }
    }

    public String decryptJwe(String jweString, byte[] aesKey) {
        try {
            SecretKey key = new SecretKeySpec(aesKey, "AES");
            JWEObject jwe = JWEObject.parse(jweString);
            jwe.decrypt(new DirectDecrypter(key));
            return jwe.getPayload().toString();
        } catch (JOSEException | java.text.ParseException e) {
            throw new RuntimeException("JWE decryption failed", e);
        }
    }

    public String encryptToJwe(String plaintext, String base64UrlKey) {
        return encryptToJwe(plaintext, Base64.getUrlDecoder().decode(base64UrlKey));
    }

    public String decryptJwe(String jweString, String base64UrlKey) {
        return decryptJwe(jweString, Base64.getUrlDecoder().decode(base64UrlKey));
    }
}
