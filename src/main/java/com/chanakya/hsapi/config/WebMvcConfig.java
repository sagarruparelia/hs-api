package com.chanakya.hsapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;

@Configuration
public class WebMvcConfig {

    private static final long CORS_MAX_AGE_SECONDS = 3600L;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Public SHL endpoints — open CORS
        CorsConfiguration shlConfig = new CorsConfiguration();
        shlConfig.addAllowedOrigin("*");
        shlConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        shlConfig.addAllowedHeader("*");
        shlConfig.setMaxAge(CORS_MAX_AGE_SECONDS);
        source.registerCorsConfiguration("/shl/**", shlConfig);

        // Secured API endpoints — restricted CORS
        CorsConfiguration secureConfig = new CorsConfiguration();
        secureConfig.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        secureConfig.setAllowedMethods(List.of("POST"));
        secureConfig.setAllowedHeaders(List.of("Content-Type", "X-Consumer-Id", "X-Request-Id"));
        secureConfig.setMaxAge(CORS_MAX_AGE_SECONDS);
        source.registerCorsConfiguration("/secure/api/**", secureConfig);
        source.registerCorsConfiguration("/graphql", secureConfig);

        return source;
    }
}
