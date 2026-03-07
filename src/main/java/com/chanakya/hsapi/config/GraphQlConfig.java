package com.chanakya.hsapi.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GraphQlConfig {

    @Bean
    public Instrumentation maxQueryComplexityInstrumentation(
            @Value("${app.graphql.max-complexity:200}") int maxComplexity) {
        return new MaxQueryComplexityInstrumentation(maxComplexity);
    }

    @Bean
    public Instrumentation maxQueryDepthInstrumentation(
            @Value("${app.graphql.max-depth:10}") int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth);
    }
}
