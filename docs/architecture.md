# Smart Health Links (SHL) - Architecture & Implementation Plan

## Context

Build `hs-api` — a Spring Boot 4.0.3 + Java 25 backend exposing:
1. **FHIR GraphQL API** — 11 resource types (10 clinical + patient demographics) with compact transforms for server-to-server consumers
2. **SHL REST API** — CRUD + public HL7 SHL protocol (snapshot with U flag + live mode with manifest POST)
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
| **GraphQL** | Spring for GraphQL 2.0 (WebMVC transport) | 11 resource type queries over HealthLake |
| **Web framework** | Spring WebMVC + Virtual Threads | REST + GraphQL endpoints |
| **Auth** | X-Consumer-Id header from OAuth2 proxy | Spring Boot trusts proxy, no token validation |
| **PDF generation** | OpenHTMLtoPDF + Thymeleaf | Accessible PDF/UA with proprietary fonts |
| **Logging** | Structured JSON | EKS auto-forwards to Splunk/Datadog (future) |

### Virtual Threads + Imperative (Confirmed)

Every code path involves blocking (HAPI FHIR, JCE, HealthLake REST, MongoDB, S3). Reactive's 3-6% throughput edge is negated by `boundedElastic` bottleneck. Virtual Threads scored 53/60 vs Reactive 33/60 in weighted analysis.

- `spring.threads.virtual.enabled=true` with `spring-boot-starter-webmvc`
- `spring-boot-starter-data-mongodb` (imperative `MongoRepository`)
- `RestClient` + SigV4 for HealthLake (not `WebClient`); AWS default credential chain works in all environments (AWS CLI locally, IAM role in EKS)
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
- `X-Consumer-Id` header required on all `/secure/api/**` endpoints → 401 if missing
- Public SHL endpoints (`/shl/{id}`) require NO auth (HL7 protocol)
- `idType` + `idValue` in request body scopes all data queries (no IDs in URLs)
- `consumerId` + `source: "external"` written to audit logs

---

## Architecture Decisions (Confirmed)

### 1. REST-Style POST Endpoints (No GET for Secured APIs)
Each operation has its own URL. All secured endpoints use POST with IDs in the request body (never in the URL). GET is reserved for the public SHL protocol endpoint only (per HL7 spec).

**Rationale:** Separate URLs per operation are self-documenting and produce clean OpenAPI specs. POST-only avoids sensitive IDs in URLs/access logs. `/secure/api` prefix makes auth boundaries explicit.

### 2. Encryption Key Storage — Application-Level Encryption
- Per-link 32-byte AES keys are encrypted with a single `ENCRYPTION_KEY` env var (AES-GCM) before MongoDB storage
- `ENCRYPTION_KEY` loaded from env var (populated via AWS EKS secret)
- Simple encrypt-on-write, decrypt-on-read — no key rotation, no versioning
- Defense-in-depth: DB breach alone doesn't expose keys (need both MongoDB + `ENCRYPTION_KEY`)
- Raw key goes into shlink URI; encrypted key stored in MongoDB `encryptionKey` field
- SHL links are short-lived (5 min to 365 days) — rotation complexity not justified for v1

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
    SecurityConfig.java           # SecurityFilterChain: public /shl/**, require X-Consumer-Id on /secure/api/**
    MongoConfig.java              # Programmatic indexes for all collections
    AwsConfig.java                # S3Client + HealthLake SigV4 + Apache5HttpClient (default credential chain)
    GraphQlConfig.java            # Instrumentation beans (complexity, depth limits)
    JacksonConfig.java            # Jackson 3 customization (HAPI FHIR uses Jackson 2 transitively, no config needed)
    WebMvcConfig.java             # CORS, security headers
    CryptoConfig.java             # Encryption/decryption bean config

  auth/
    ExternalAuthFilter.java       # OncePerRequestFilter: validates X-Consumer-Id
    RequestContext.java           # ScopedValue: consumerId, source, requestId, enterpriseId

  shl/
    ShlController.java            # POST /secure/api/v1/shl/* (search, get, preview, counts, history, create, revoke)
    ShlPublicController.java      # GET /shl/{id}?recipient={org} (U flag), POST /shl/{id} (manifest, live mode), OPTIONS /shl/{id}
    model/
      ShlLinkDocument.java        # @Document("shl_links")
      ShlAuditLogDocument.java    # @Document("shl_audit_log")
      ShlMode.java                # Enum: SNAPSHOT | LIVE
      ShlStatus.java              # Enum: ACTIVE, REVOKED
      ShlFlag.java                # Constants + validation: U (direct-file), L (long-term) — Phase 2 adds P (passcode). Stored as String for flag combinations ("LP")
      AccessRecord.java           # Record: recipient, action, timestamp — embedded in ShlLinkDocument
      ShlAuditAction.java         # Enum: LINK_CREATED, LINK_VIEWED, LINK_REVOKED, LINK_ACCESSED, LINK_ACCESS_EXPIRED, LINK_ACCESS_REVOKED, LINK_DENIED
      FhirResourceType.java       # Enum: MEDICATION, IMMUNIZATION, ALLERGY, etc.
    dto/
      ShlSearchRequest.java       # Record: idType, idValue, hsid_uuid?
      ShlCreateRequest.java       # Record: idType, idValue, label, expiresAt (ISO 8601, 5min–365d), selectedResources, includePdf, patientName
      ShlRevokeRequest.java       # Record: idType, idValue, hsid_uuid
      ShlLinkResponse.java        # Record: link detail with effectiveStatus, shlinkUrl, qrData
      ShlCreateResponse.java      # Record: hsid_uuid, shlinkUrl, qrData, expiresAt
      ShlCountsResponse.java      # Record: per-resource-type counts
      ShlAccessHistoryResponse.java # Record: hsid_uuid, history[] — reads from shl_links.accessHistory (single doc read)
      ManifestResponse.java        # Record: status, list?, files[] (manifest POST for live/non-U links)
    service/
      ShlService.java             # Create, search, get, preview, counts, revoke, history
      ShlRetrievalService.java    # Public retrieval: GET (U flag snapshot) + POST (manifest for live mode)
      ShlinkBuilder.java          # Build shlink:/ URIs (link ID: 32-byte SecureRandom, base64url)
    repository/
      ShlLinkRepository.java      # MongoRepository for shl_links
      ShlAuditLogRepository.java  # MongoRepository for shl_audit_log

  crypto/
    EncryptionService.java        # JWE encrypt/decrypt (Nimbus JOSE: dir, A256GCM)
    KeyGenerationService.java     # 32-byte AES key gen + base64url
    FieldEncryptionService.java   # AES-GCM encrypt/decrypt for MongoDB field-level encryption using ENCRYPTION_KEY

  fhir/
    FhirClient.java               # HealthLake client: RestClient + SigV4 (AWS CLI creds locally, IAM role in EKS)
    SigV4RequestInterceptor.java  # ClientHttpRequestInterceptor for HealthLake
    FhirBundleBuilder.java        # Build PatientSharedBundle (see PSHD Bundle Requirements below)
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
    PatientController.java        # @QueryMapping patientSummary(enterpriseId) — demographics
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
      PatientTransform.java       # Patient → PatientSummary (demographics)
    type/                         # GraphQL response records
      MedicationType.java         # record(name, status, dosage, reason, startDate)
      ImmunizationType.java
      ... (10 clinical types + PatientSummaryType matching schema)
      ResourceCountsType.java
      HealthDashboardType.java

  crosswalk/
    PatientCrosswalkDocument.java # @Document("patient_crosswalk")
    PatientCrosswalkRepository.java
    PatientCrosswalkService.java  # enterpriseId → healthLakePatientId lookup

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
    @Id private String id;                           // 32-byte SecureRandom, base64url (256-bit entropy, 43 chars)
    private String enterpriseId;                    // iMDM member ID, indexed
    private String label;                           // Max 80 chars
    private ShlMode mode;                           // SNAPSHOT | LIVE
    private String flag;                            // "U" (snapshot) or "L" (live). Phase 2: "LP" — alphabetically concatenated
    private String encryptionKey;                   // AES-GCM encrypted with ENCRYPTION_KEY (base64url)
    private List<FhirResourceType> selectedResources;
    private boolean includePdf;
    private String patientName;
    private Instant expiresAt;                      // REQUIRED — ISO 8601 format, min 5 minutes, max 365 days from creation
    private ShlStatus status;                       // ACTIVE | REVOKED
    private String s3Key;                           // Snapshot only: "shl/{eid}/{id}.jwe"
    private Instant createdAt;
    private List<AccessRecord> accessHistory;       // Embedded compact access log (operational)
    // Computed: effectiveStatus (ACTIVE + past expiry → EXPIRED)
    // Phase 2 fields: passcodeHash, maxAttempts, failedAttempts
}

// Embedded in ShlLinkDocument — compact, patient/admin-facing
record AccessRecord(
    String recipient,                               // "Dr. Smith's Office"
    String action,                                  // ACCESSED | DENIED | ACCESS_EXPIRED | ACCESS_REVOKED
    Instant timestamp
) {}
```

**Two access log stores, two purposes:**

| Store | Purpose | Audience | Contents |
|-------|---------|----------|----------|
| `shl_links.accessHistory[]` | Operational — "who accessed my link?" | Patient/admin via `/history` | Compact: recipient, action, timestamp |
| `shl_audit_log` | Compliance — system-wide forensic trail | Security/audit team | Full: IP, UA, contentHash, requestId, consumerId |

- U flag (short-lived snapshot links): access count per link is low (1-5), no growth concern
- L flag (long-term live links): cap embedded list at 50 entries via `$push` + `$slice: -50`; older entries remain in `shl_audit_log`

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

One enterpriseId maps to one HealthLake patient resource ID.

```java
@Document("patient_crosswalk")
public class PatientCrosswalkDocument {
    @Id private String id;
    private String enterpriseId;                    // Unique index — iMDM member ID
    private String healthLakePatientId;             // 1:1 mapping
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
POST /secure/api/v1/graphql
X-Consumer-Id: <consumer-id>
```
11 queries + resourceCounts + healthDashboard

### SHL Secured Endpoints (Authenticated — all POST, IDs in body)
```
POST /secure/api/v1/shl/search    →  List links for an enterprise ID
POST /secure/api/v1/shl/get       →  Get single link detail
POST /secure/api/v1/shl/preview   →  Preview link content
POST /secure/api/v1/shl/counts    →  Resource type counts for a patient (per enterpriseId from HealthLake)
POST /secure/api/v1/shl/history   →  Access history (who accessed, allowed/denied)
POST /secure/api/v1/shl/create    →  Create new SHL link
POST /secure/api/v1/shl/revoke    →  Revoke an existing link
```

**Common request body pattern:**
```json
{
  "idType": "EID",
  "idValue": "actual-enterprise-id",
  "hsid_uuid": "link-id-if-needed"
}
```
- `idType` + `idValue`: primary lookup key (EID = enterprise ID from iMDM)
- `hsid_uuid`: link ID — required for get/preview/counts/history/revoke, absent for search/create

### SHL Public (No Auth — HL7 Protocol)
```
GET     /shl/{id}?recipient={org}   →  Direct file retrieval (U flag — returns JWE, Content-Type: application/jose)
POST    /shl/{id}                   →  Manifest retrieval (live mode — returns manifest JSON with embedded JWE)
OPTIONS /shl/{id}                   →  CORS preflight
```

### Infrastructure
```
GET /health  →  Health check (no auth)
```

---

## Core Flows

### Flow 1: FHIR GraphQL Query
```
1. ExternalAuthFilter validates X-Consumer-Id
2. GraphQL resolver extracts enterpriseId from query variable
3. PatientCrosswalkService looks up healthLakePatientId (1:1)
4. FhirClient fetches FHIR Bundle from HealthLake for the patient ID
5. Transform converts Bundle entries to compact GraphQL types
6. Date filter (startDate/endDate, inclusive) + sort by resource date (default: most recent first)
7. Audit: write to audit_log (action: "fhir_query", resourceType)
8. Return compact response
```

**Date filter + sort** is a common pattern on all 11 queries (10 clinical + patientSummary):
- `startDate` / `endDate` (optional, inclusive)
- `sortOrder` (ASC/DESC, default: DESC = most recent first)
- Each resource type has a canonical date field (e.g., MedicationRequest.authoredOn, Immunization.occurrenceDateTime)

### Flow 2: SHL Create (Snapshot, U flag)
```
1. Validate X-Consumer-Id + request body (expiresAt REQUIRED, ISO 8601 format, min 5 min / max 365 days from now — return 400 if missing or out of range)
2. Generate link ID: 32-byte SecureRandom → base64url (43 chars, 256-bit entropy)
3. PatientCrosswalkService looks up healthLakePatientId (1:1)
4. Generate 32-byte AES key → FieldEncryptionService.encrypt(ENCRYPTION_KEY) → encrypted key
5. FhirBundleBuilder: fetch selectedResources from HealthLake → build PatientSharedBundle
6. Optional: PdfGenerationService (OpenHTMLtoPDF + Thymeleaf) → DocumentReference entry
7. Encrypt Bundle → JWE (dir, A256GCM) using raw AES key
8. Upload JWE to S3: shl/{enterpriseId}/{linkId}.jwe
9. Insert ShlLinkDocument to shl_links (encryptionKey = encrypted, flag = "U")
10. Build shlink URI (raw key in URI, NOT the encrypted key)
11. Audit: LINK_CREATED to shl_audit_log
12. Return linkId, shlinkUrl, qrData, expiresAt
```

### Flow 3: SHL Direct File Retrieval (GET /shl/{id} — U flag)
```
1. Validate recipient query param (required per spec; return 400 if missing/empty)
2. Find link in shl_links (not found → audit LINK_DENIED to shl_audit_log + 404)
3. Check status:
   - revoked → push AccessRecord(recipient, ACCESS_REVOKED, now) to shl_links.accessHistory
              → write full audit to shl_audit_log (recipient, IP, UA) → 404
   - expired → push AccessRecord(recipient, ACCESS_EXPIRED, now) to shl_links.accessHistory
              → write full audit to shl_audit_log (recipient, IP, UA) → 404
4. Download JWE from S3
5. Push AccessRecord(recipient, ACCESSED, now) to shl_links.accessHistory
   Write full audit to shl_audit_log (contentHash, recipient, IP, UA)
6. Return JWE string, Content-Type: application/jose
```

### Flow 4: SHL Create (Live mode)
```
Same as Flow 2 EXCEPT steps 5-8 skipped (no bundle built, no S3 upload).
s3Key is null. flag = "L". Bundle built on-demand at retrieval time.
```

### Flow 5: SHL Manifest Retrieval (POST /shl/{id} — live mode)
```
1. Parse JSON body: { recipient (required), passcode? (Phase 2+), embeddedLengthMax? }
2. Find link in shl_links (not found → audit LINK_DENIED + 404)
3. Check status:
   - revoked/expired → return manifest { status: "no-longer-valid", files: [] }
4. Build fresh Bundle from HealthLake, encrypt → JWE
5. Push AccessRecord(recipient, ACCESSED, now) to shl_links.accessHistory
   Write full audit to shl_audit_log (contentHash, recipient, IP, UA)
6. Return manifest: { status: "can-change", files: [{ contentType: "application/fhir+json", embedded: JWE }] }
```

### Flow 6: resourceCounts (Parallel)
```
1. PatientCrosswalkService looks up healthLakePatientId (1:1)
2. CompletableFuture.allOf() — parallel HealthLake calls per resource type
3. Return ResourceCounts
```

---

## Security Configuration

```
/shl/**              -> permitAll()         # SHL protocol (public, key-in-URL is the auth)
/health              -> permitAll()         # Health checks
/secure/api/**       -> ExternalAuthFilter  # Requires X-Consumer-Id header
everything else      -> denyAll()
```

- No OAuth2 in Spring Boot — proxy handles authentication
- CSRF disabled (API-only, no browser sessions)
- CORS: open on `/shl/**`, restricted on `/secure/api/**`
- Security headers matching Next.js on all responses
- HealthLake access: AWS SigV4 request signing via default credential chain
- **Network assumption:** ALB + security groups ensure only OAuth2 proxy can reach `/secure/api/**` endpoints. `X-Consumer-Id` cannot be spoofed from outside the VPC. Public `/shl/**` endpoints are internet-facing; brute-force is mitigated by 256-bit entropy in link IDs (2^256 possible IDs).

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
- `url`: manifest/file endpoint — max 128 chars, must contain 256+ bits of entropy
- `flag`: alphabetically concatenated flags. v1: `"U"` (snapshot) or `"L"` (live). Phase 2: `"LP"`, etc.
- `key`: 32-byte symmetric key, base64url-encoded (43 chars)
- `exp`: REQUIRED per PSHD profile (UNIX epoch seconds)
- `label`: optional, max 80 chars

### Flag Reference
| Flag | Meaning | v1 Scope | Constraints |
|------|---------|----------|-------------|
| U | Direct file access (GET returns JWE) | Yes | Cannot coexist with P |
| L | Long-term (content may evolve, live mode) | Yes | Can coexist with P (Phase 2); not with U |
| P | Passcode required (manifest POST needs `passcode`) | Phase 2 | Cannot coexist with U; requires lifetime attempt tracking |

### SHLink URL Format
```
shlink:/eyJ1cmwiOiJodHRwczovL...base64url-encoded-payload
```

### JWE Encryption Parameters
- Algorithm: `dir` (direct encryption with shared symmetric key)
- Encryption: `A256GCM` (AES-256-GCM authenticated encryption)
- JWE `cty` header: `application/fhir+json`
- HTTP response Content-Type: `application/jose`
- Compression: `DEF` (DEFLATE, optional)
- Nimbus JOSE handles unique nonce/IV per encryption automatically

### PSHD Bundle Requirements (PatientSharedBundle)
The `FhirBundleBuilder` must produce a compliant PatientSharedBundle:
```
Bundle (type: collection, timestamp: required)
├── Patient (1..1, required)
├── DocumentReference (0..1, if includePdf=true)
│   ├── status: current
│   ├── type: LOINC 60591-5 (Patient summary Document)
│   ├── category: patient-shared (from patient-shared-category CodeSystem)
│   ├── author: reference to Patient
│   ├── content.attachment.contentType: application/pdf
│   ├── content.attachment.data: base64-encoded PDF
│   └── securityLabel: PATAST (patient asserted)
└── Discrete FHIR resources (1..*, from selectedResources)
    └── Conditions, Medications, Observations, etc.
```
- Resources SHOULD NOT include `meta.profile` — receivers SHALL NOT require it
- At minimum: Patient + at least one of (DocumentReference with PDF, discrete FHIR resources)

---

## Build Sequence

### Step 0: Update Documentation
- Rewrite `docs/architecture.md` with updated architecture
- Add `docker-compose.yaml` (MongoDB only — HealthLake accessed via AWS CLI creds)
- Install fhir-developer + aws-skills Claude Code skills

### Step 1: Project Foundation
- `pom.xml` — full dependency overhaul
- `application.yaml` + `application-dev.yaml` — profiles
- `config/SecurityConfig.java` — filter chain
- `config/MongoConfig.java` — indexes for all 4 collections
- `config/AwsConfig.java` — S3Client + Apache5HttpClient
- `config/JacksonConfig.java` — Jackson 3 customization (Jackson 2 via HAPI transitive, no dual-stack config)
- `config/WebMvcConfig.java` — CORS, security headers
- `auth/ExternalAuthFilter.java` — X-Consumer-Id validation
- `auth/RequestContext.java` — ScopedValue
- `common/filter/SecurityHeadersFilter.java`
- `common/filter/RequestIdFilter.java`
- `docker-compose.yaml` — MongoDB only (HealthLake accessed via AWS CLI creds in all environments)
- **Verify**: App starts, connects to MongoDB, health check returns 200

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
- `crypto/FieldEncryptionService.java` — AES-GCM encrypt/decrypt using `ENCRYPTION_KEY` env var
- `config/CryptoConfig.java` — Bean wiring
- **Verify**: Generate key → field-encrypt → field-decrypt → JWE encrypt → JWE decrypt round-trip

### Step 5: S3 + FHIR Client
- `storage/S3PayloadService.java`
- `fhir/FhirClient.java` — single implementation, SigV4 auth via default credential chain (AWS CLI locally, IAM role in EKS)
- `fhir/SigV4RequestInterceptor.java`
- `fhir/FhirSerializationService.java`
- **Verify**: S3 round-trip, FHIR client fetches from HealthLake (use `aws sso login` locally)

### Step 6: FHIR Transforms + Bundle Builder
- `graphql/transform/*.java` — 11 transforms (10 clinical + patient demographics)
- `graphql/type/*.java` — 11 types + ResourceCounts + HealthDashboard records
- `fhir/FhirBundleBuilder.java`
- **Verify**: Unit tests for each transform matching Node.js output shapes

### Step 7: GraphQL API
- `src/main/resources/graphql/schema.graphqls` — full 11-query schema
- `graphql/*Controller.java` — 11 controllers + resourceCounts + healthDashboard
- `config/GraphQlConfig.java`
- **Verify**: GraphQL queries return correct data, date filtering works, audit logged

### Step 8: SHL Data Model + Service
- `shl/model/ShlLinkDocument.java`
- `shl/repository/ShlLinkRepository.java`
- `shl/service/ShlService.java`
- `shl/service/ShlinkBuilder.java`
- **Verify**: CRUD operations, shlink URI format matches Node.js, effectiveStatus computed

### Step 9: SHL API + PDF
- `shl/ShlController.java` — POST /secure/api/v1/shl/* (search, get, preview, counts, history, create, revoke)
- `pdf/PdfGenerationService.java` — OpenHTMLtoPDF + Thymeleaf
- `src/main/resources/templates/pdf/patient-summary.html`
- **Verify**: All 7 endpoints work, audit trail, PDF generation with accessible tags

### Step 10: SHL Public Retrieval (GET + POST)
- `shl/ShlPublicController.java` — GET (U flag snapshot) + POST (manifest, live mode) + OPTIONS
- `shl/service/ShlRetrievalService.java`
- GET: validate `recipient` query param (required, return 400 if missing/empty)
- POST: parse JSON body `{ recipient }`, build fresh Bundle for live mode, return manifest
- **Verify**: E2E snapshot (create → GET → decrypt) + E2E live (create → POST manifest → decrypt)

### Step 11: Structured JSON Logging
- Configure structured JSON log format (EKS auto-forwards to Splunk/Datadog)
- Log fields: requestId, consumerId, enterpriseId, action, duration, status
- **Verify**: Logs output valid JSON, contain required fields

**Deferred (Phase 2):**
- P flag (passcode lifecycle: hash storage, lifetime attempt limits, 401 + remainingAttempts)
- `location` URLs (S3 pre-signed URLs as alternative to embedded JWE)
- `embeddedLengthMax` handling in manifest POST
- Manifest `list` field (optional FHIR List resource)
- Rate limiting (ALB vs app-level TBD)
- Splunk/Datadog integration
- Custom metrics

**Phase 2 P flag extensibility:** `ShlLinkDocument` already stores `flag` as a String (not enum) to support future flag combinations. Adding P requires: `passcodeHash` (bcrypt), `maxAttempts` (int, default 10), `failedAttempts` (int, default 0) fields. P and U are mutually exclusive — validate on creation.

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
| 1 | Crypto round-trip (key gen → field-encrypt → field-decrypt → JWE encrypt → JWE decrypt) | 4 |
| 2 | Field encryption: encrypted key in MongoDB is not plaintext, decrypts back to original | 4 |
| 3 | FHIR transforms match Node.js compact output shapes | 6 |
| 4 | PSHD Bundle structure: Patient + DocumentReference (LOINC 60591-5) + discrete resources | 6 |
| 5 | Crosswalk: enterpriseId → healthLakePatientId (1:1) | 3 |
| 6 | SHL create (snapshot, U flag): JWE in S3, link in MongoDB, audit logged | 8-9 |
| 7 | SHL create rejects missing expiresAt with 400 | 8-9 |
| 7b | SHL create rejects expiresAt < 5 min or > 365 days from now with 400 | 8-9 |
| 8 | Link ID has 256-bit entropy (32-byte SecureRandom, base64url, 43 chars) | 8 |
| 9 | SHL snapshot retrieval: GET with recipient → JWE → decrypt → valid FHIR | 10 |
| 10 | SHL retrieval rejects missing/empty recipient with 400 | 10 |
| 11 | SHL retrieval of revoked link → audit LINK_ACCESS_REVOKED + 404 | 10 |
| 12 | SHL retrieval of expired link → audit LINK_ACCESS_EXPIRED + 404 | 10 |
| 13 | GraphQL: 11 queries (10 clinical + patientSummary), date filtering, resourceCounts | 7 |
| 14 | healthDashboard: all 11 resource types in parallel | 7 |
| 15 | Auth: /secure/api/** without X-Consumer-Id → 401 | 1 |
| 16 | Auth: /shl/{id} without auth → 200 (public) | 10 |
| 17 | Audit: every action (SHL + fhir_query) produces correct log with recipient | 3-10 |
| 18 | Security headers match Next.js on all responses | 1 |
| 19 | CORS: open on /shl/**, restricted on /api/** | 1 |
| 20 | effectiveStatus: active + expired → "expired" | 8 |
| 21 | E2E snapshot: API → create snapshot link → GET retrieval → decrypt → valid Bundle | 12 |
| 22 | E2E live: API → create live link → POST manifest → decrypt embedded JWE → valid Bundle | 12 |
| 23 | SHL create live mode: no S3 upload, s3Key null, flag "L" | 8-9 |
| 24 | SHL manifest POST: returns { status: "can-change", files: [...] } for live links | 10 |
| 25 | Virtual thread pinning: none with -Djdk.tracePinnedThreads=full | 12 |
| 26 | Structured JSON logs contain requestId, consumerId, action | 11 |
| 27 | shlink URI format matches Node.js exactly | 8 |
