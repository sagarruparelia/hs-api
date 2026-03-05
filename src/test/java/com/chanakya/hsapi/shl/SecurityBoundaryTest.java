package com.chanakya.hsapi.shl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityBoundaryTest {

    @ServiceConnection
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7");

    static {
        mongoDBContainer.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void securedEndpoint_withoutConsumerId_returns401() throws Exception {
        mockMvc.perform(post("/secure/api/v1/shl/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"idType":"EID","idValue":"test-123"}"""))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void securedEndpoint_withConsumerId_returns200or404() throws Exception {
        mockMvc.perform(post("/secure/api/v1/shl/search")
                .header("X-Consumer-Id", "test-consumer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"idType":"EID","idValue":"test-123"}"""))
            .andExpect(status().isOk());
    }

    @Test
    void publicEndpoint_withoutAuth_isAccessible() throws Exception {
        mockMvc.perform(get("/shl/nonexistent-id")
                .param("recipient", "Dr. Smith"))
            .andExpect(status().isNotFound());
    }

    @Test
    void publicEndpoint_missingRecipient_returns400() throws Exception {
        mockMvc.perform(get("/shl/some-id"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void healthCheck_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void securityHeaders_arePresent() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"));
    }

    @Test
    void unknownPath_returnsForbidden() throws Exception {
        mockMvc.perform(get("/unknown"))
            .andExpect(status().isForbidden());
    }
}
