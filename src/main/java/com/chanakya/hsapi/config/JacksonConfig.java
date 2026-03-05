package com.chanakya.hsapi.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    // Spring Boot 4 auto-configures Jackson 3 (tools.jackson) with JavaTimeModule.
    // HAPI FHIR uses Jackson 2 (com.fasterxml.jackson) transitively — different packages, no conflict.
    // No custom configuration needed for v1.
}
