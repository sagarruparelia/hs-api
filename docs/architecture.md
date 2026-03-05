# Smart Health Links (SHL) - Architecture & Implementation Plan

## Context

Build `hs-api` — a Spring Boot 4.0.3 + Java 25 backend exposing:
1. **FHIR GraphQL API** — 10 clinical resource types with compact transforms for server-to-server consumers
2. **SHL REST API** — CRUD + public HL7 SHL protocol (snapshot + live modes)
3. **Data layer** — MongoDB, S3, HealthLake (owned by Spring Boot)

**Reference**: The existing Node.js implementation (hs-web-poc) serves as design inspiration. Spring Boot owns the API contract and data schema. The Next.js frontend will consume Spring Boot APIs.

**Current state**: Empty Spring Boot 4.0.3 + Java 25 skeleton. Package: `com.chanakya.hsapi`.

**Spec sources**:
- Must-have: https://hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/rkGeS5cIZe
- Must-have: https://hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/SJHzQ1aDbx
- Long-term: https://build.fhir.org/ig/HL7/smart-health-cards-and-links/links-specification.html

---

## Infrastructure Decisions

| Concern | Technology | Role |
|---------|-----------|------|
| **FHIR data store** | AWS HealthLake (FHIR R4 v4.0.1 only) | Authoritative store for all FHIR resources |
| **Transactional DB** | MongoDB (imperative + virtual threads) | SHL metadata, audit logs, patient crosswalk |
| **SHL payload storage** | AWS S3 | Encrypted JWE blobs (snapshot mode payloads) |
| **GraphQL** | Spring for GraphQL 2.0 (WebMVC transport) | 12 resource type queries over HealthLake |
| **Web framework** | Spring WebMVC + Virtual Threads | REST + GraphQL endpoints |
| **Auth** | X-Consumer-Id header from OAuth2 proxy | Spring Boot trusts proxy, no token validation |
| **PDF generation** | OpenHTMLtoPDF + Thymeleaf | Accessible PDF/UA with proprietary fonts |
| **Logging** | Structured JSON | EKS auto-forwards to Splunk/Datadog (future) |

### Virtual Threads + Imperative (Confirmed)

Every code path involves blocking (HAPI FHIR, JCE, HealthLake REST, MongoDB, S3). Reactive's 3-6% throughput edge is negated by `boundedElastic` bottleneck. Virtual Threads scored 53/60 vs Reactive 33/60 in weighted analysis.

- `spring.threads.virtual.enabled=true` with `spring-boot-starter-webmvc`
- `spring-boot-starter-data-mongodb` (imperative `MongoRepository`)
- `RestClient` for HealthLake (not `WebClient`)
- MongoDB driver 5.6.x is virtual-thread-safe
- Parallel fan-out via `CompletableFuture` (StructuredTaskScope still preview in Java 25)

### Jackson 2/3 Coexistence

HAPI FHIR 8.x uses Jackson 2 (`com.fasterxml.jackson`) — brought transitively. Spring Boot 4 uses Jackson 3 (`tools.jackson`). Different Java packages, no conflict. No explicit Jackson 2 dependency or dual-stack config needed. Just use `FhirContext` for FHIR serialization; Spring handles HTTP serialization with Jackson 3 automatically.

---

## Auth & Trust Model

```
Consumer → OAuth2 Proxy (client credentials) → ALB (mTLS) → Spring Boot (trusts X-Consumer-Id)
```

- Spring Boot does NOT validate OAuth2 tokens
- `X-Consumer-Id` header required on all `/api/**` endpoints → 401 if missing
- Public SHL endpoints (`/shl/{id}`) require NO auth (HL7 protocol)
- `enterpriseId` in query params/body scopes all data queries
- `consumerId` + `source: "external"` written to audit logs

---

## Architecture Decisions (Confirmed)

### 1. Action-Based API Style
POST for reads (list, get, preview, counts), PATCH for mutations (create, revoke). GET used only for public `/shl/{id}` protocol endpoints. No DELETE verb.

### 2. Encryption Key Storage — Envelope Encryption
- Per-link 32-byte AES keys are wrapped (AES-KW) using a master key before MongoDB storage
- Master key loaded from env var `SHL_MASTER_KEY` (populated via AWS EKS secret)
- `MasterKeyService` interface with `EnvMasterKeyService` implementation
- Future: `KmsMasterKeyService` for AWS KMS without code changes
- Raw key goes into shlink URI; wrapped key stored in MongoDB `encryptionKey` field

### 3. PDF Generation — OpenHTMLtoPDF + Thymeleaf
- `com.openhtmltopdf:openhtmltopdf-pdfbox:1.1.22` (Apache 2.0 license)
- Thymeleaf HTML templates with CSS `@font-face` for proprietary fonts
- PDF/UA tagged structure for screen readers, WCAG 2.0, Section 508
- Fonts embedded via `src/main/resources/fonts/`

---

## Package Structure

```
com.chanakya.hsapi/
  HsApiApplication.java

  config/
    SecurityConfig.java           # SecurityFilterChain: public /shl/**, require X-Consumer-Id on /api/**
    MongoConfig.java              # Programmatic indexes for all collections
    AwsConfig.java                # S3Client + Apache5HttpClient
    GraphQlConfig.java            # Instrumentation beans (complexity, depth limits)
    JacksonConfig.java            # Jackson 3 customization (HAPI FHIR uses Jackson 2 transitively, no config needed)
    WebMvcConfig.java             # CORS, security headers
    CryptoConfig.java             # Encryption/decryption bean config

  auth/
    ExternalAuthFilter.java       # OncePerRequestFilter: validates X-Consumer-Id
    RequestContext.java           # ScopedValue: consumerId, source, requestId, enterpriseId

  shl/
    ShlApiController.java         # POST /api/v1/shl (list, get, preview, counts)
    ShlMutationController.java    # PATCH /api/v1/shl (create, revoke)
    ShlPublicController.java      # GET /shl/{id}, POST /shl/{id}, OPTIONS /shl/{id}
    model/
      ShlLinkDocument.java        # @Document("shl_links")
      ShlAuditLogDocument.java    # @Document("shl_audit_log")
      ShlMode.java                # Enum: SNAPSHOT, LIVE
      ShlStatus.java              # Enum: ACTIVE, REVOKED
      ShlFlag.java                # Enum: U (single-use), L (long-term), P (passcode) — protocol flags for shlink URI
      ShlAuditAction.java         # Enum: LINK_CREATED, LINK_VIEWED, LINK_REVOKED, LINK_ACCESSED, LINK_ACCESS_EXPIRED, LINK_ACCESS_REVOKED, LINK_DENIED
      FhirResourceType.java       # Enum: MEDICATION, IMMUNIZATION, ALLERGY, etc.
    dto/
      ShlActionRequest.java       # Record: action, enterpriseId, linkId? (POST reads)
      ShlMutationRequest.java     # Record: action, enterpriseId, linkId?, label?, mode?, selectedResources?, etc. (PATCH mutations)
      ShlLinkResponse.java        # Record: link detail with effectiveStatus, shlinkUrl, qrData
      ShlCreateResponse.java      # Record: linkId, shlinkUrl, qrData, expiresAt
      ShlCountsResponse.java      # Record: per-resource-type counts
      ManifestResponse.java       # Record: status, files[] (live mode)
    service/
      ShlService.java             # Create, list, get, preview, counts, revoke
      ShlRetrievalService.java    # Public snapshot GET + live POST
      ShlinkBuilder.java          # Build shlink:/ URIs
    repository/
      ShlLinkRepository.java      # MongoRepository for shl_links
      ShlAuditLogRepository.java  # MongoRepository for shl_audit_log

  crypto/
    EncryptionService.java        # JWE encrypt/decrypt (Nimbus JOSE: dir, A256GCM)
    KeyGenerationService.java     # 32-byte AES key gen + base64url
    MasterKeyService.java         # Interface: wrapKey(raw) → encrypted, unwrapKey(encrypted) → raw
    EnvMasterKeyService.java      # AES-KW using env var SHL_MASTER_KEY (EKS secret)
    # Future: KmsMasterKeyService.java (AWS KMS GenerateDataKey/Decrypt)

  fhir/
    FhirClient.java               # Interface: search by resource type, get patient
    LocalFhirClient.java          # @Profile("dev"): RestClient → local HAPI FHIR
    HealthLakeFhirClient.java     # @Profile("!dev"): RestClient + SigV4
    SigV4RequestInterceptor.java  # ClientHttpRequestInterceptor for HealthLake
    FhirBundleBuilder.java        # Build CMS PatientSharedBundle from selected resources
    FhirSerializationService.java # FhirContext.forR4() singleton, Jackson 2

  graphql/
    MedicationController.java     # @QueryMapping medications(enterpriseId, startDate, endDate, sortOrder)
    ImmunizationController.java
    AllergyController.java
    ConditionController.java
    ProcedureController.java
    LabResultController.java
    CoverageController.java
    ClaimController.java
    AppointmentController.java
    CareTeamController.java
    ResourceCountsController.java # @QueryMapping resourceCounts, healthDashboard
    transform/                    # FHIR Bundle → compact GraphQL types
      MedicationTransform.java    # MedicationRequest → Medication
      ImmunizationTransform.java
      AllergyTransform.java
      ConditionTransform.java
      ProcedureTransform.java
      LabResultTransform.java
      CoverageTransform.java
      ClaimTransform.java
      AppointmentTransform.java
      CareTeamTransform.java
    type/                         # GraphQL response records
      MedicationType.java         # record(name, status, dosage, reason, startDate)
      ImmunizationType.java
      ... (10 clinical types matching schema)
      ResourceCountsType.java
      HealthDashboardType.java

  crosswalk/
    PatientCrosswalkDocument.java # @Document("patient_crosswalk")
    PatientCrosswalkRepository.java
    PatientCrosswalkService.java  # enterpriseId → List<healthLakePatientId> lookup

  pdf/
    PdfGenerationService.java     # CMS PatientSharedDocumentReference PDF
    # Uses OpenHTMLtoPDF + Thymeleaf templates
    # Templates: src/main/resources/templates/pdf/patient-summary.html
    # Fonts: src/main/resources/fonts/ (proprietary, CSS @font-face)
    # Accessible PDF/UA with tagged structure for screen readers

  storage/
    S3PayloadService.java         # Upload/retrieve/delete JWE from S3

  audit/
    AuditLogDocument.java         # @Document("audit_log") — general audit (fhir_query)
    AuditLogRepository.java
    AuditService.java             # Writes to audit_log + shl_audit_log

  common/
    exception/
      GlobalExceptionHandler.java
      ErrorResponse.java          # Record: error, message, timestamp, path
    filter/
      SecurityHeadersFilter.java  # Security headers matching Next.js
      RequestIdFilter.java        # Generate requestId for every request
```

---

## Data Model

`enterpriseId` is a unique member identifier from internal iMDM — used to scope all queries.

### `shl_links` Collection

```java
@Document("shl_links")
public class ShlLinkDocument {
    @Id private String id;
    private String enterpriseId;                    // iMDM member ID, indexed
    private String label;                           // Max 80 chars
    private ShlMode mode;                           // SNAPSHOT | LIVE
    private String encryptionKey;                   // Wrapped AES key (base64url)
    private List<FhirResourceType> selectedResources;
    private boolean includePdf;
    private String patientName;
    private Instant expiresAt;
    private ShlStatus status;                       // ACTIVE | REVOKED
    private String s3Key;                           // Snapshot only: "shl/{eid}/{id}.jwe"
    private Instant createdAt;
    // Computed: effectiveStatus (ACTIVE + past expiry → EXPIRED)
}
```

Indexes: `{enterpriseId, status}`, `{expiresAt}`

### `shl_audit_log` Collection

```java
@Document("shl_audit_log")
public class ShlAuditLogDocument {
    @Id private String id;
    private String linkId;
    private String enterpriseId;
    private ShlAuditAction action;                  // Enum: 7 audit actions
    private String recipient;
    private Map<String, Object> detail;
    private String ipAddress;
    private String userAgent;
    private String requestId;
    private String consumerId;                      // From X-Consumer-Id
    private String source;                          // "external"
    private Instant timestamp;
}
```

Indexes: `{linkId, timestamp desc}`, `{enterpriseId, timestamp desc}`

### `patient_crosswalk` Collection

One enterpriseId can map to multiple HealthLake patient resource IDs. All FHIR queries fan out across all mapped IDs and aggregate results.

```java
@Document("patient_crosswalk")
public class PatientCrosswalkDocument {
    @Id private String id;
    private String enterpriseId;                    // Unique index — iMDM member ID
    private List<String> healthLakePatientIds;      // One-to-many mapping
}
```

### `audit_log` Collection

```java
@Document("audit_log")
public class AuditLogDocument {
    @Id private String id;
    private String enterpriseId;
    private String action;                          // "fhir_query"
    private FhirResourceType resourceType;
    private String resourceId;
    private Map<String, Object> detail;
    private String ipAddress;
    private String userAgent;
    private String requestId;
    private String consumerId;
    private String source;                          // "external"
    private Instant timestamp;
}
```

---

## API Endpoints

### FHIR GraphQL (Authenticated)
```
POST /api/v1/graphql
X-Consumer-Id: <consumer-id>
```
10 queries + resourceCounts + healthDashboard

### SHL CRUD (Authenticated)
```
POST  /api/v1/shl   →  action: list | get | preview | counts | accessHistory   (reads)
PATCH /api/v1/shl   →  action: create | revoke                                (mutations)
```

### SHL Public (No Auth — HL7 Protocol)
```
GET     /shl/{id}?recipient={org}   →  Snapshot retrieval (flag "U")
POST    /shl/{id}                   →  Live manifest retrieval (flag "L")
OPTIONS /shl/{id}                   →  CORS preflight
```

### Infrastructure
```
GET /api/health  →  Health check (no auth)
```

---

## Core Flows

### Flow 1: FHIR GraphQL Query
```
1. ExternalAuthFilter validates X-Consumer-Id
2. GraphQL resolver extracts enterpriseId from query variable
3. PatientCrosswalkService looks up List<healthLakePatientId>
4. FhirClient fetches FHIR Bundles from HealthLake for EACH patient ID (parallel fan-out)
5. Aggregate all Bundle entries across patients
6. Transform converts entries to compact GraphQL types
7. Date filter (startDate/endDate, inclusive) + sort by resource date (default: most recent first)
8. Audit: write to audit_log (action: "fhir_query", resourceType)
9. Return compact response
```

**Date filter + sort** is a common pattern on all 10 clinical queries:
- `startDate` / `endDate` (optional, inclusive)
- `sortOrder` (ASC/DESC, default: DESC = most recent first)
- Each resource type has a canonical date field (e.g., MedicationRequest.authoredOn, Immunization.occurrenceDateTime)
```

### Flow 2: SHL Create (Snapshot)
```
1. Validate X-Consumer-Id + request body
2. PatientCrosswalkService looks up List<healthLakePatientId>
3. Generate 32-byte AES key → MasterKeyService.wrapKey() → encrypted key
4. FhirBundleBuilder: fetch selectedResources from HealthLake → build Bundle
5. Optional: PdfGenerationService (OpenHTMLtoPDF + Thymeleaf) → DocumentReference entry
6. Encrypt Bundle → JWE (dir, A256GCM) using raw AES key
7. Upload JWE to S3: shl/{enterpriseId}/{linkId}.jwe
8. Insert ShlLinkDocument to shl_links (encryptionKey = wrapped/encrypted)
9. Build shlink URI (raw key in URI, NOT the wrapped key)
10. Audit: link_created to shl_audit_log
11. Return linkId, shlinkUrl, qrData, expiresAt
```

### Flow 3: SHL Create (Live)
```
Same as snapshot EXCEPT steps 4-7 skipped (no bundle built, no S3 upload).
s3Key is null. Bundle built on-demand at retrieval time.
```

### Flow 4: SHL Snapshot Retrieval (GET /shl/{id})
```
1. Validate recipient query param
2. Find link in shl_links (not found → audit LINK_DENIED + 404)
3. Check status:
   - revoked → audit LINK_ACCESS_REVOKED (recipient, IP, UA) → 404
   - expired → audit LINK_ACCESS_EXPIRED (recipient, IP, UA) → 404
   - live mode → 405 (Allow: POST)
4. Download JWE from S3
5. Audit: LINK_ACCESSED with contentHash (SHA-256 of JWE), recipient, IP, UA
6. Return JWE string, Content-Type: application/jose
```

### Flow 5: SHL Live Retrieval (POST /shl/{id})
```
1. Parse JSON body, validate recipient
2. Find link in shl_links (not found → audit LINK_DENIED + 404)
3. Check status:
   - revoked → audit LINK_ACCESS_REVOKED (recipient, IP, UA) → 404
   - expired → audit LINK_ACCESS_EXPIRED (recipient, IP, UA) → 404
   - snapshot mode → 405 (Allow: GET)
4. MasterKeyService.unwrapKey(encryptionKey) → raw AES key
5. Build fresh Bundle from HealthLake using selectedResources + patientName
6. Encrypt with raw AES key → JWE
7. Audit: link_accessed
8. Return manifest: { status: "can-change", files: [{ contentType, embedded: JWE, lastUpdated }] }
```

### Flow 6: resourceCounts (Parallel)
```
1. PatientCrosswalkService looks up List<healthLakePatientId>
2. CompletableFuture.allOf() — parallel HealthLake calls per resource type × patient ID
3. Aggregate counts across all patient IDs per resource type
4. Return ResourceCounts
```

---

## Security Configuration

```
/shl/**              -> permitAll()         # SHL protocol (public, key-in-URL is the auth)
/api/health          -> permitAll()         # Health checks
/api/**              -> ExternalAuthFilter  # Requires X-Consumer-Id header
everything else      -> denyAll()
```

- No OAuth2 in Spring Boot — proxy handles authentication
- CSRF disabled (API-only, no browser sessions)
- CORS: open on `/shl/**`, restricted on `/api/**`
- Security headers matching Next.js on all responses
- HealthLake access: AWS SigV4 request signing via default credential chain

---

## Dependencies

### Consolidated Version Reference

| Technology | Version | Artifact |
|---|---|---|
| JDK | 25 (LTS) | openjdk-25 |
| Spring Boot | 4.0.3 | spring-boot-starter |
| Spring Framework | 7.0.x | spring-framework-bom |
| Spring for GraphQL | 2.0.x | spring-boot-starter-graphql |
| Spring Security | 7.0.x | spring-boot-starter-security |
| Spring Data MongoDB | 5.0.x | spring-boot-starter-data-mongodb |
| Jackson 3 | 3.0.x | tools.jackson:jackson-bom (Spring default) |
| Jackson 2 | 2.18.x | com.fasterxml.jackson (transitive via HAPI FHIR) |
| HAPI FHIR | 8.6.2 | ca.uhn.hapi.fhir:hapi-fhir-base |
| AWS SDK Java v2 | 2.42.4 (BOM) | software.amazon.awssdk:bom |
| Nimbus JOSE+JWT | 10.8 | com.nimbusds:nimbus-jose-jwt |
| OpenHTMLtoPDF | 1.1.22 | com.openhtmltopdf:openhtmltopdf-pdfbox |
| Testcontainers | 2.0.3 | org.testcontainers:testcontainers-mongodb |

### Replace from Current pom.xml
| Remove | Add |
|--------|-----|
| `spring-boot-starter-webflux` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-data-mongodb-reactive` | `spring-boot-starter-data-mongodb` |
| `spring-boot-starter-security-oauth2-client` | `spring-boot-starter-security` |
| All `*-reactive-test`, `*-oauth2-client-test` | See test deps below |
| `org.projectlombok:lombok` + annotation processor | (removed — use records) |

### Add
```xml
<!-- GraphQL -->
spring-boot-starter-graphql
spring-boot-starter-graphql-test (test)

<!-- JWE Encryption -->
com.nimbusds:nimbus-jose-jwt:10.8

<!-- FHIR R4 (Jackson 2 internally) -->
ca.uhn.hapi.fhir:hapi-fhir-base:8.6.2
ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.6.2

<!-- AWS SDK v2 -->
software.amazon.awssdk:bom:2.42.4 (dependencyManagement)
software.amazon.awssdk:s3
software.amazon.awssdk:healthlake
software.amazon.awssdk:apache5-client

<!-- PDF Generation (Accessible PDF/UA + Thymeleaf templates) -->
com.openhtmltopdf:openhtmltopdf-pdfbox:1.1.22
com.openhtmltopdf:openhtmltopdf-slf4j:1.1.22
org.springframework.boot:spring-boot-starter-thymeleaf

<!-- Test -->
org.testcontainers:testcontainers-mongodb:2.0.3
```

---

## SHL Protocol Details

### SHLink Payload Structure (encoded in URL)
```json
{
  "url": "https://api.example.com/shl/{id}",
  "flag": "U",
  "key": "rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q",
  "exp": 1706745600,
  "label": "Patient's health summary"
}
```

### SHLink URL Format
```
shlink:/eyJ1cmwiOiJodHRwczovL...base64url-encoded-payload
```

### JWE Encryption Parameters
- Algorithm: `dir` (direct encryption with shared symmetric key)
- Encryption: `A256GCM` (AES-256-GCM authenticated encryption)
- Content Type: `application/fhir+json`
- Compression: `DEF` (DEFLATE, optional)

---

## Build Sequence

### Step 0: Update Documentation
- Rewrite `docs/architecture.md` with updated architecture
- Add `docker-compose.yaml` (MongoDB + HAPI FHIR server)
- Install fhir-developer + aws-skills Claude Code skills

### Step 1: Project Foundation
- `pom.xml` — full dependency overhaul
- `application.yaml` + `application-dev.yaml` — profiles
- `config/SecurityConfig.java` — filter chain
- `config/MongoConfig.java` — indexes for all 4 collections
- `config/AwsConfig.java` — S3Client + Apache5HttpClient
- `config/JacksonConfig.java` — Jackson 2/3 dual-stack
- `config/WebMvcConfig.java` — CORS, security headers
- `auth/ExternalAuthFilter.java` — X-Consumer-Id validation
- `auth/RequestContext.java` — ScopedValue
- `common/filter/SecurityHeadersFilter.java`
- `common/filter/RequestIdFilter.java`
- `docker-compose.yaml` — MongoDB + HAPI FHIR
- **Verify**: App starts with `dev` profile, connects to MongoDB, health check returns 200

### Step 2: Common Infrastructure
- `common/exception/GlobalExceptionHandler.java`
- `common/exception/ErrorResponse.java`
- **Verify**: Unit tests pass

### Step 3: Crosswalk + Audit
- `crosswalk/PatientCrosswalkDocument.java` + Repository + Service
- `audit/AuditLogDocument.java` + Repository
- `shl/model/ShlAuditLogDocument.java` + Repository
- `audit/AuditService.java`
- **Verify**: Integration test (Testcontainers): crosswalk lookup, audit write + read

### Step 4: Crypto Module
- `crypto/KeyGenerationService.java` — 32-byte key gen
- `crypto/EncryptionService.java` — JWE encrypt/decrypt (dir, A256GCM)
- `crypto/MasterKeyService.java` — Interface: wrapKey/unwrapKey
- `crypto/EnvMasterKeyService.java` — AES-KW with env var `SHL_MASTER_KEY`
- `config/CryptoConfig.java` — Bean wiring
- **Verify**: Generate key → wrap → unwrap → encrypt → decrypt round-trip

### Step 5: S3 + FHIR Client
- `storage/S3PayloadService.java`
- `fhir/FhirClient.java` interface
- `fhir/LocalFhirClient.java` (@Profile "dev")
- `fhir/HealthLakeFhirClient.java` (@Profile "!dev")
- `fhir/SigV4RequestInterceptor.java`
- `fhir/FhirSerializationService.java`
- **Verify**: S3 round-trip, FHIR client fetches from local HAPI FHIR

### Step 6: FHIR Transforms + Bundle Builder
- `graphql/transform/*.java` — 10 transforms
- `graphql/type/*.java` — 12 + ResourceCounts + HealthDashboard records
- `fhir/FhirBundleBuilder.java`
- **Verify**: Unit tests for each transform matching Node.js output shapes

### Step 7: GraphQL API
- `src/main/resources/graphql/schema.graphqls` — full 10-query schema
- `graphql/*Controller.java` — 10 controllers + resourceCounts + healthDashboard
- `config/GraphQlConfig.java`
- **Verify**: GraphQL queries return correct data, date filtering works, audit logged

### Step 8: SHL Data Model + Service
- `shl/model/ShlLinkDocument.java`
- `shl/repository/ShlLinkRepository.java`
- `shl/service/ShlService.java`
- `shl/service/ShlinkBuilder.java`
- **Verify**: CRUD operations, shlink URI format matches Node.js, effectiveStatus computed

### Step 9: SHL API + PDF
- `shl/ShlApiController.java` — POST /api/v1/shl (list, get, preview, counts)
- `shl/ShlMutationController.java` — PATCH /api/v1/shl (create, revoke)
- `pdf/PdfGenerationService.java` — OpenHTMLtoPDF + Thymeleaf
- `src/main/resources/templates/pdf/patient-summary.html`
- **Verify**: All CRUD actions, audit trail, PDF generation with accessible tags

### Step 10: SHL Public Retrieval
- `shl/ShlPublicController.java` — GET + POST + OPTIONS
- `shl/service/ShlRetrievalService.java`
- 405 responses with Allow header for wrong method
- **Verify**: Full E2E — snapshot GET + live POST retrieval

### Step 11: Structured JSON Logging
- Configure structured JSON log format (EKS auto-forwards to Splunk/Datadog)
- Log fields: requestId, consumerId, enterpriseId, action, duration, status
- **Verify**: Logs output valid JSON, contain required fields

**Deferred (future phases):**
- Rate limiting (ALB vs app-level TBD)
- Splunk/Datadog integration
- Custom metrics

### Step 12: Integration Tests
- Full E2E flow tests (Testcontainers)
- Security boundary tests (auth, CORS, headers)
- API contract tests (Next.js consumes Spring Boot APIs)
- Virtual thread pinning test
- **Verify**: `./mvnw verify` passes

---

## Verification Plan

| # | Test | Step |
|---|------|------|
| 1 | Crypto round-trip (key gen → wrap → unwrap → encrypt → decrypt) | 4 |
| 2 | FHIR transforms match Node.js compact output shapes | 6 |
| 3 | Crosswalk: enterpriseId → healthLakePatientId | 3 |
| 4 | SHL create (snapshot): JWE in S3, link in MongoDB, audit logged | 8-9 |
| 5 | SHL create (live): no S3 upload, link in MongoDB | 8-9 |
| 6 | SHL snapshot retrieval: GET → JWE → decrypt → valid FHIR | 10 |
| 7 | SHL live retrieval: POST → manifest with embedded JWE | 10 |
| 8 | SHL wrong method: GET on live → 405, POST on snapshot → 405 | 10 |
| 9 | GraphQL: 10 clinical queries, date filtering, resourceCounts | 7 |
| 10 | healthDashboard: all 10 clinical types in parallel | 7 |
| 11 | Auth: /api/** without X-Consumer-Id → 401 | 1 |
| 12 | Auth: /shl/{id} without auth → 200 (public) | 10 |
| 13 | Audit: every action (5 SHL + fhir_query) produces correct log | 3-10 |
| 14 | Security headers match Next.js on all responses | 1 |
| 15 | CORS: open on /shl/**, restricted on /api/** | 1 |
| 16 | effectiveStatus: active + expired → "expired" | 8 |
| 17 | E2E: API → create link → retrieve via public endpoint | 12 |
| 18 | Virtual thread pinning: none with -Djdk.tracePinnedThreads=full | 12 |
| 19 | Structured JSON logs contain requestId, consumerId, action | 11 |
| 20 | shlink URI format matches Node.js exactly | 8 |
