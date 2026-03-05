package com.chanakya.hsapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Public SHL endpoints — open CORS
        registry.addMapping("/shl/**")
            .allowedOrigins("*")
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);

        // Secured API endpoints — restricted CORS
        registry.addMapping("/secure/api/**")
            .allowedOrigins(allowedOrigins.split(","))
            .allowedMethods("POST")
            .allowedHeaders("Content-Type", "X-Consumer-Id", "X-Request-Id")
            .maxAge(3600);
    }
}
