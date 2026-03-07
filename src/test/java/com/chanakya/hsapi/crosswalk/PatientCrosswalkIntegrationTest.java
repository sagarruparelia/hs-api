package com.chanakya.hsapi.crosswalk;

import com.chanakya.hsapi.audit.AuditService;
import com.chanakya.hsapi.auth.ExternalAuthFilter;
import com.chanakya.hsapi.common.filter.RequestIdFilter;
import com.chanakya.hsapi.shl.model.ShlAuditAction;
import com.chanakya.hsapi.shl.model.ShlAuditLogDocument;
import com.chanakya.hsapi.shl.repository.ShlAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.mongodb.MongoDBContainer;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientCrosswalkIntegrationTest {

    @ServiceConnection
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:8");

    static {
        mongoDBContainer.start();
    }

    @Autowired
    private PatientCrosswalkRepository crosswalkRepository;

    @Autowired
    private PatientCrosswalkService crosswalkService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ShlAuditLogRepository shlAuditLogRepository;

    @BeforeEach
    void setUp() {
        crosswalkRepository.deleteAll();
        shlAuditLogRepository.deleteAll();
    }

    @Test
    void crosswalkLookup_findsExistingMapping() {
        var doc = new PatientCrosswalkDocument("ENT-001", "HL-PAT-123");
        crosswalkRepository.save(doc);

        String patientId = crosswalkService.resolveHealthLakePatientId("ENT-001");
        assertEquals("HL-PAT-123", patientId);
    }

    @Test
    void crosswalkLookup_throwsForMissingMapping() {
        assertThrows(NoSuchElementException.class,
            () -> crosswalkService.resolveHealthLakePatientId("NONEXISTENT"));
    }

    @Test
    void auditWrite_andRead() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTR, "test-req-001");
        request.setAttribute(ExternalAuthFilter.CONSUMER_ID_ATTR, "test-consumer");
        request.setAttribute(ExternalAuthFilter.SOURCE_ATTR, "proxy");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "test-agent");

        auditService.logShlAction("link-001", "ENT-001", ShlAuditAction.LINK_CREATED,
            null, Map.of("test", "data"), request);

        List<ShlAuditLogDocument> logs = shlAuditLogRepository.findByLinkIdOrderByTimestampDesc("link-001");
        assertEquals(1, logs.size());
        assertEquals(ShlAuditAction.LINK_CREATED, logs.getFirst().getAction());
        assertEquals("ENT-001", logs.getFirst().getEnterpriseId());
        assertEquals("test-consumer", logs.getFirst().getConsumerId());
    }
}
