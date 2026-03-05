package com.chanakya.hsapi.crypto;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class KeyGenerationService {

    private final SecureRandom secureRandom = new SecureRandom();

    public byte[] generateAesKey() {
        byte[] key = new byte[32];
        secureRandom.nextBytes(key);
        return key;
    }

    public String generateAesKeyBase64Url() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(generateAesKey());
    }

    public String generateLinkId() {
        byte[] id = new byte[32];
        secureRandom.nextBytes(id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    }
}
