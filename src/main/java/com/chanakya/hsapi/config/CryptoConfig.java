package com.chanakya.hsapi.config;

import com.chanakya.hsapi.crypto.FieldEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {

    @Value("${app.encryption-key:}")
    private String encryptionKey;

    @Bean
    public FieldEncryptionService fieldEncryptionService() {
        return new FieldEncryptionService(encryptionKey);
    }
}
