# Smart Health Links (SHL) - Architecture & Implementation Plan

## Context

Build a **Patient App** that generates SMART Health Links (SHL) enabling patients to share encrypted FHIR health data with providers via QR codes. The app implements the Patient-Shared Health Document via SHL spec (must-have), with a foundation extensible to the full SHL protocol (long-term).

**Current state**: Empty Spring Boot 4.0.3 + Java 25 skeleton. Package: `com.chanakya.hsapi`.

**Spec sources**:
- Must-have: https://hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/rkGeS5cIZe
- Must-have: https://hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/SJHzQ1aDbx
- Long-term: https://build.fhir.org/ig/HL7/smart-health-cards-and-links/links-specification.html

---

## Infrastructure Decisions

| Concern | Technology | Role |
|---------|-----------|------|
| **FHIR data store** | AWS HealthLake (FHIR R4 v4.0.1 only) | Authoritative store for Patient, DocumentReference, Bundles, all FHIR resources |
| **Transactional DB** | MongoDB (imperative + virtual threads) | SHL metadata, audit logs, app state |
| **SHL payload storage** | AWS S3 | Encrypted JWE blobs (the actual shared payloads) |
| **GraphQL** | Spring for GraphQL 2.0 (WebMVC transport) | Query layer over FHIR resources from HealthLake |
| **Web framework** | Spring WebMVC + Virtual Threads | REST endpoints (SHL protocol) + GraphQL |
| **Observability** | Micrometer 1.16.x + OTLP | Metrics, traces via OpenTelemetry |
| **Rate limiting** | Bucket4j 8.16.1 (core API, no starter) | API rate limiting with MongoDB backend |

### Critical: Virtual Threads + Imperative over Reactive

The skill inventory confirms: with `spring.threads.virtual.enabled=true`, **imperative MongoRepository gives equivalent I/O scalability to reactive** with far simpler code. This means:

- **Switch from `spring-boot-starter-webflux` to `spring-boot-starter-webmvc`**
- **Switch from `spring-boot-starter-data-mongodb-reactive` to `spring-boot-starter-data-mongodb`**
- Use `MongoRepository` (not `ReactiveMongoRepository`)
- Use `RestClient` (not `WebClient`) for HealthLake calls
- MongoDB driver 5.6.x is virtual-thread-safe by default

### Critical: Jackson 2/3 Dual-Stack

HAPI FHIR 8.x uses Jackson 2 (`com.fasterxml.jackson`) exclusively and does NOT support Jackson 3. Spring Boot 4 defaults to Jackson 3 (`tools.jackson`). Both must coexist:

- Jackson 3 (`tools.jackson`, `JsonMapper`): Used by Spring MVC/GraphQL for HTTP serialization
- Jackson 2 (`com.fasterxml.jackson`, `ObjectMapper`): Used by HAPI FHIR for FHIR resource serialization
- **Never let HAPI FHIR serialize through Spring's default `JsonMapper`**
- **Never register `@JsonComponent` beans expecting them to apply to FHIR resources**
- Explicitly include `com.fasterxml.jackson.core:jackson-databind:2.18+` for HAPI FHIR
- Configure `spring.jackson2.*` properties for HAPI FHIR contexts

---

## Package Structure

```
com.chanakya.hsapi/
  HsApiApplication.java

  config/
    SecurityConfig.java           # Public /shl/** vs authenticated /api/**
    MongoConfig.java              # Imperative MongoDB, programmatic indexes
    AwsConfig.java                # S3Client, HealthLake RestClient, Apache5HttpClient
    GraphQlConfig.java            # Instrumentation beans (complexity, depth limits)
    CryptoConfig.java             # Master key bean (env dev / KMS prod)
    JacksonConfig.java            # Jackson 3 for Spring + Jackson 2 isolation for HAPI FHIR
    WebMvcConfig.java             # CORS, content negotiation

  shl/                            # Smart Health Link domain
    ShlController.java            # Public protocol endpoints: GET/POST /shl/{id}
    ShlManagementController.java  # RestController: /api/v1/shl/** (authenticated)
    model/
      ShlDocument.java            # @Document - SHL metadata in MongoDB
      ShlStatus.java              # ACTIVE, EXPIRED, REVOKED
      ShlFlag.java                # U, P, L
    dto/
      ShlPayload.java             # Record: SHLink JSON (url, key, exp, flag, label, v)
      CreateShlRequest.java       # Record: create SHL request
      CreateShlResponse.java      # Record: shlinkUrl + base64url payload
      ShlSummary.java             # Record: patient-facing SHL list item
      ManifestRequest.java        # Record: Phase 2 POST body
      ManifestResponse.java       # Record: Phase 2 manifest
    service/
      ShlService.java             # Create, list, revoke SHLs
      ShlRetrievalService.java    # Public retrieval (U-flag GET, future POST)
    repository/
      ShlRepository.java          # MongoRepository (imperative)

  crypto/                         # Encryption domain
    EncryptionService.java        # JWE: alg=dir, enc=A256GCM (Nimbus JOSE)
    KeyGenerationService.java     # 32-byte AES key gen + base64url
    MasterKeyService.java         # Interface for key encryption at rest
    LocalMasterKeyService.java    # Dev profile: env var AES key
    KmsMasterKeyService.java      # Prod profile: AWS KMS

  fhir/                           # FHIR + HealthLake domain
    FhirBundleService.java        # Build PatientSharedBundle (HAPI FHIR R4)
    FhirSerializationService.java # FhirContext.forR4() singleton, Jackson 2 isolation
    HealthLakeClient.java         # RestClient-based FHIR REST client with SigV4
    dto/
      UploadHealthDataRequest.java
      PatientInfo.java
      DocumentInfo.java
      HealthDataSummary.java
    controller/
      HealthDataController.java   # RestController: /api/v1/health-data/**

  graphql/                        # GraphQL layer over FHIR
    PatientGraphQlController.java     # @BatchMapping for associations
    DocumentReferenceGraphQlController.java
    BundleGraphQlController.java
    type/                         # GraphQL Java types mapping FHIR resources
      PatientType.java
      DocumentReferenceType.java
      BundleType.java

  storage/                        # S3 storage for JWE payloads
    S3PayloadService.java         # Upload/retrieve encrypted JWE from S3

  audit/                          # Audit logging domain
    AuditService.java             # Audit logging to MongoDB
    AuditController.java          # RestController: /api/v1/audit/**
    model/
      AuditLogDocument.java       # @Document - every interaction in MongoDB
      AuditAction.java            # SHL_CREATED, SHL_ACCESSED, etc.
      AuditOutcome.java           # SUCCESS, FAILURE, DENIED
    dto/
      AuditLogEntry.java          # Record: patient-facing audit entry
    repository/
      AuditLogRepository.java     # MongoRepository (imperative)

  common/
    exception/
      GlobalExceptionHandler.java     # @RestControllerAdvice
      ShlNotFoundException.java
      ShlExpiredException.java
      ShlAccessDeniedException.java
      ErrorResponse.java
    util/
      Base64UrlUtil.java
      SecureIdGenerator.java      # 256-bit cryptographic random IDs
```

---

## Data Architecture

### MongoDB Collections (Transactional Data) — Imperative + Virtual Threads

**`smart_health_links`** - SHL metadata only (no payload data)

Fields:
- `id` (String) - 256-bit secure random, base64url (43 chars)
- `patientId` (String) - Owner
- `managementToken` (String) - For app-level SHL management
- `encryptedAesKey` (byte[]) - AES key encrypted with server master key
- `label` (String) - Max 80 chars
- `flags` (String) - "U" (Phase 1), "LP", "LU" (Phase 2)
- `expiresAt` (Instant) - TTL
- `status` (ShlStatus) - ACTIVE, EXPIRED, REVOKED
- `s3PayloadKey` (String) - S3 object key for the JWE blob
- `healthLakeResourceId` (String) - HealthLake Bundle resource ID
- `contentType` (String) - "application/fhir+json"
- `passcodeHash` (String) - Phase 2: BCrypt hash (nullable)
- `remainingPasscodeAttempts` (Integer) - Phase 2 (nullable)
- `createdAt`, `updatedAt` (Instant)
- `accessCount` (int)

Indexes: `{patientId, status}`, `{expiresAt} TTL +24h grace`, `{status}`

**`audit_logs`** - Every interaction logged

Fields:
- `id`, `shlId`, `patientId`
- `action` (AuditAction) - SHL_CREATED, SHL_ACCESSED, SHL_ACCESS_FAILED, SHL_EXPIRED, SHL_REVOKED, HEALTH_DATA_UPLOADED, HEALTH_DATA_UPDATED, AUDIT_VIEWED, PASSCODE_FAILED
- `outcome` (AuditOutcome) - SUCCESS, FAILURE, DENIED
- `recipientOrg` - From ?recipient= parameter
- `ipAddress`, `userAgent`, `errorDetail`
- `timestamp` (Instant)
- `metadata` (Map) - Extensible

Indexes: `{shlId, timestamp desc}`, `{patientId, timestamp desc}`, `{timestamp} TTL 90d`

### AWS HealthLake (FHIR Data Store)

- FHIR R4 v4.0.1 ONLY (never attempt R5 resources)
- FHIR REST API accessed via RestClient with AWS SigV4 signing
- CRUD: POST /Patient, GET /Patient/{id}, POST /Bundle, GET /Bundle/{id}
- HealthLake handles FHIR validation, indexing, NLP, and search
- `FhirContext.forR4()` must be used for all HealthLake interactions

### AWS S3 (Encrypted Payload Storage)

- Bucket: `{project}-shl-payloads`
- Object key pattern: `shl/{shlId}/payload.jwe`
- Pre-encrypted JWE compact serialization blobs
- SSE-S3 encryption as additional layer
- Lifecycle rule: auto-delete after TTL

---

## Core Flows

### Flow 1: Upload Health Data
```
Patient App -> POST /api/v1/health-data (UploadHealthDataRequest)
  1. Build FHIR R4 PatientSharedBundle via FhirBundleService (HAPI FHIR, Jackson 2)
     - Patient resource with demographics
     - DocumentReference: status=current, type=LOINC 60591-5,
       category=patient-shared, embedded PDF
     - Additional FHIR resources
  2. POST Bundle to HealthLake via HealthLakeClient (RestClient + SigV4)
  3. HealthLake validates, indexes, returns resource ID
  4. Log HEALTH_DATA_UPLOADED audit event
  5. Return HealthDataSummary with healthLakeResourceId
```

### Flow 2: Create SHL
```
Patient App -> POST /api/v1/shl (CreateShlRequest)
  1. Fetch FHIR Bundle from HealthLake by healthLakeResourceId
  2. Serialize Bundle to FHIR JSON via FhirSerializationService (Jackson 2 path)
  3. Generate 32-byte AES key via KeyGenerationService
  4. Encrypt FHIR JSON -> JWE (alg=dir, enc=A256GCM, cty=application/fhir+json)
  5. Upload JWE blob to S3 (shl/{shlId}/payload.jwe)
  6. Encrypt AES key with master key for at-rest storage
  7. Save ShlDocument to MongoDB (metadata only, s3PayloadKey reference)
  8. Build ShlPayload: {url, key, exp, flag:"U", label}
  9. Base64url-encode -> shlink:/ URL
  10. Log SHL_CREATED audit event
  11. Return CreateShlResponse (shlId, shlinkUrl, shlinkPayload)
      Note: QR code generation is client-side (UI responsibility)
```

### Flow 3: Provider Retrieves SHL (Public - No Auth)
```
Provider EHR -> GET /shl/{id}?recipient={org-name}
  1. Validate: recipient param required (else 400)
  2. Find ShlDocument in MongoDB
  3. Check status (REVOKED -> 410), expiration (past -> 404)
  4. Fetch JWE blob from S3 by s3PayloadKey
  5. Increment accessCount in MongoDB
  6. Log SHL_ACCESSED audit event (recipient, IP, user-agent)
  7. Return: 200 OK, Content-Type: application/jose, body: JWE string

  On failure:
  - Log SHL_ACCESS_FAILED with error detail
  - Return appropriate HTTP status (404/410/400)
```

### Flow 4: GraphQL Query (Authenticated)
```
Patient App -> POST /graphql
  1. GraphQL controller calls HealthLakeClient
  2. HealthLakeClient queries HealthLake FHIR REST API
  3. Maps FHIR resources -> GraphQL types
  4. Uses @BatchMapping for associations (prevent N+1)
  5. Returns structured data
```

---

## API Endpoints

### Public (No Auth) - SHL Protocol
| Method | Path | Response Type | Description |
|--------|------|---------------|-------------|
| GET | /shl/{id}?recipient={org} | application/jose | U-flag direct JWE retrieval |
| POST | /shl/{id} | application/json | Phase 2: manifest retrieval |

### Authenticated - Management APIs
| Method | Path | Description |
|--------|------|-------------|
| POST | /api/v1/health-data | Upload health data to HealthLake |
| GET | /api/v1/health-data | List patient's health data |
| DELETE | /api/v1/health-data/{id} | Delete health data |
| POST | /api/v1/shl | Create SHL from health data |
| GET | /api/v1/shl | List patient's SHLs |
| GET | /api/v1/shl/{id} | Get SHL details |
| DELETE | /api/v1/shl/{id} | Revoke SHL |
| GET | /api/v1/audit | Patient-wide audit log |
| GET | /api/v1/shl/{id}/audit | SHL-specific audit log |

### GraphQL - FHIR Resource Queries
| Path | Description |
|------|-------------|
| POST /graphql | Patient, DocumentReference, Bundle queries (Connection-spec pagination) |

---

## Dependencies

### Consolidated version reference

| Technology | Version | Artifact |
|---|---|---|
| JDK | 25.0.2 (LTS) | openjdk-25 |
| Spring Boot | 4.0.3 | spring-boot-starter |
| Spring Framework | 7.0.5 | spring-framework-bom |
| Spring for GraphQL | 2.0.x | spring-boot-starter-graphql |
| Spring Security | 7.0.3 | spring-boot-starter-security |
| Spring Data MongoDB | 5.0.x | spring-boot-starter-data-mongodb |
| Jackson 3 | 3.0.x | tools.jackson:jackson-bom (Spring default) |
| Jackson 2 | 2.18.x | com.fasterxml.jackson (for HAPI FHIR) |
| GraphQL Java | 25.0 | com.graphql-java:graphql-java |
| HAPI FHIR | 8.6.2 | ca.uhn.hapi.fhir:hapi-fhir-base |
| AWS SDK Java v2 | 2.42.4 (BOM) | software.amazon.awssdk:bom |
| Nimbus JOSE+JWT | 10.8 | com.nimbusds:nimbus-jose-jwt |
| Micrometer | 1.16.x | io.micrometer:micrometer-bom |
| Testcontainers | 2.0.3 | org.testcontainers:testcontainers-bom |
| Bucket4j | 8.16.1 | com.bucket4j:bucket4j_jdk17-core |
| MongoDB Java Driver | 5.6.3 | org.mongodb:mongodb-driver-sync |

### Add to pom.xml

```xml
<!-- Web (replace webflux) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>

<!-- MongoDB imperative (replace reactive) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>

<!-- GraphQL -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-graphql</artifactId>
</dependency>

<!-- JWE Encryption -->
<dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>10.8</version>
</dependency>

<!-- FHIR R4 (uses Jackson 2 internally - exclude Jetty) -->
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-base</artifactId>
    <version>8.6.2</version>
    <exclusions>
        <exclusion><groupId>org.eclipse.jetty</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>org.eclipse.jetty.ee10</groupId><artifactId>*</artifactId></exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-structures-r4</artifactId>
    <version>8.6.2</version>
    <exclusions>
        <exclusion><groupId>org.eclipse.jetty</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>org.eclipse.jetty.ee10</groupId><artifactId>*</artifactId></exclusion>
    </exclusions>
</dependency>

<!-- Jackson 2 for HAPI FHIR (explicit, alongside Jackson 3) -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.18.3</version>
</dependency>

<!-- AWS SDK v2 BOM (in dependencyManagement) -->
<!-- version: 2.42.4 -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>healthlake</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>kms</artifactId>
</dependency>
<!-- Apache 5 HTTP client for virtual thread safety -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>apache5-client</artifactId>
</dependency>

<!-- Rate limiting -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j_jdk17-core</artifactId>
    <version>8.16.1</version>
</dependency>
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j_jdk17-mongodb-sync</artifactId>
    <version>8.16.1</version>
</dependency>

<!-- Observability -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>

<!-- Test -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-graphql-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-mongodb</artifactId>
    <version>2.0.3</version>
    <scope>test</scope>
</dependency>
```

### Remove from pom.xml
- `spring-boot-starter-webflux` (replace with `spring-boot-starter-webmvc`)
- `spring-boot-starter-data-mongodb-reactive` (replace with `spring-boot-starter-data-mongodb`)
- `org.projectlombok:lombok` and its annotation processor config
- All `*-reactive-test` dependencies

---

## GraphQL Schema

File: `src/main/resources/graphql/schema.graphqls`

Use `ConnectionTypeDefinitionConfigurer` to auto-generate Connection/Edge/PageInfo types. Only define node types.

```graphql
type Query {
    patient(id: ID!): Patient
    patients: [Patient!]!
    documentReferences(patientId: ID!, first: Int, after: String): DocumentReferenceConnection!
    bundle(id: ID!): Bundle
}

type Patient {
    id: ID!
    name: [HumanName!]
    birthDate: String
    gender: String
    identifier: [Identifier!]
    documentReferences: [DocumentReference!]!
}

type HumanName {
    family: String
    given: [String!]
}

type Identifier {
    system: String
    value: String
}

type DocumentReference {
    id: ID!
    status: String!
    type: CodeableConcept
    category: [CodeableConcept!]
    date: String
    content: [DocumentContent!]
}

type CodeableConcept {
    coding: [Coding!]
    text: String
}

type Coding {
    system: String
    code: String
    display: String
}

type DocumentContent {
    attachment: Attachment
}

type Attachment {
    contentType: String
    title: String
    size: Int
}

type Bundle {
    id: ID!
    type: String!
    timestamp: String
    totalEntries: Int
}
```

---

## Security Configuration

```
/shl/**              -> permitAll()     # SHL protocol (public, key-in-URL is the auth)
/actuator/health     -> permitAll()     # Health checks
/graphql             -> authenticated() # Patient queries
/api/**              -> authenticated() # Management APIs
everything else      -> denyAll()
```

- OAuth2 Client for patient-facing login flow
- CSRF disabled (API-only)
- HealthLake access: AWS SigV4 request signing via default credential chain
- Master key: env var for local dev, AWS KMS for staging/prod (via Spring profiles)

---

## Key Architectural Decisions

1. **Imperative + Virtual Threads over Reactive** - `spring.threads.virtual.enabled=true` with imperative MongoRepository gives equivalent I/O scalability without reactive complexity. MongoDB driver 5.6.x is virtual-thread-safe. Switch from WebFlux to WebMVC.

2. **Jackson 2/3 dual-stack** - HAPI FHIR 8.x requires Jackson 2; Spring Boot 4 defaults to Jackson 3. Both coexist. FHIR serialization uses isolated Jackson 2 `ObjectMapper`. Spring MVC/GraphQL uses Jackson 3 `JsonMapper`.

3. **Pre-encrypt at SHL creation, store JWE in S3** - Retrieval is fast (S3 fetch + return), no crypto on hot path. S3 provides durable blob storage.

4. **HealthLake for FHIR, not MongoDB** - HealthLake handles FHIR validation, indexing, NLP, and compliance. R4 only.

5. **MongoDB for metadata + audit only** - SHL metadata is transactional. Audit needs fast writes. Imperative driver with virtual threads.

6. **Spring for GraphQL 2.0 with @BatchMapping** - Official Spring integration, `@BatchMapping` prevents N+1. `ConnectionTypeDefinitionConfigurer` auto-generates pagination types.

7. **Apache 5 HTTP client for AWS SDK** - Default Apache 4.5 client pins virtual threads. Apache 5 (`apache5-client`, GA since v2.41.0) is virtual-thread-safe.

8. **256-bit secure random SHL IDs** - SHL spec requires 256+ bits of entropy in the URL.

9. **No Lombok** - Spring Boot 4 best practice: use Java records for DTOs, explicit code for entities.

10. **Dual key management** - Environment variable master key for local dev, AWS KMS for staging/prod. MasterKeyService interface abstracts the difference.

11. **QR code generation is client-side** - Server returns shlinkUrl and base64url-encoded payload. UI generates QR codes.

12. **Bucket4j core API for rate limiting** - No Spring Boot 4 starter yet. Use `bucket4j_jdk17-core` with `bucket4j_jdk17-mongodb-sync` for distributed rate limiting.

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

### FHIR Bundle Structure (PatientSharedBundle)
```
Bundle (type: collection, timestamp: required)
  Entry[0]: Patient (demographics, identifier)
  Entry[1]: DocumentReference
    - status: current
    - type: LOINC 60591-5 (Patient summary Document)
    - category: patient-shared
    - content.attachment: base64-encoded PDF (application/pdf)
  Entry[2..n]: Additional FHIR resources (Conditions, Medications, Observations)
```

### Expiration Guidance
| Scenario | Suggested Expiration |
|----------|---------------------|
| In-person visit (QR on phone) | 5-15 minutes |
| Printed QR for appointment | 24-48 hours |
| Ongoing relationship | Patient preference |

---

## Phase 2 Extension Points (Built into Phase 1, Not Implemented)

| Feature | Extension Point |
|---------|----------------|
| Manifest POST | ShlController POST handler returns 501 stub |
| Passcode (P flag) | ShlDocument.passcodeHash, remainingPasscodeAttempts (nullable) |
| Long-term links (L flag) | healthLakeResourceId enables re-fetch + re-encrypt |
| Multiple content types | contentType field, extensible manifest file entries |
| File locations | S3 presigned URLs in ManifestResponse (max 1 hour) |
| Status tracking | ShlStatus enum extensible to FINALIZED, CAN_CHANGE, NO_LONGER_VALID |

---

## Build Sequence

### Step 0: Install Skills + Write Docs
- Install fhir-developer and aws-skills Claude Code skills
- Save CLAUDE.md with skill inventory rules

### Step 1: Project Foundation
- Modify pom.xml: replace webflux with webmvc, reactive-mongo with imperative, add all dependencies, remove Lombok, add AWS BOM, add start-class property
- Expand application.yaml: MongoDB (spring.mongodb.*), AWS (HealthLake endpoint, S3 bucket), OAuth2, crypto, SHL config, `spring.threads.virtual.enabled=true`
- Create config classes: SecurityConfig, MongoConfig, AwsConfig (Apache5HttpClient), CryptoConfig, JacksonConfig (dual-stack)
- **Verify**: App starts, connects to MongoDB, S3 client initializes

### Step 2: Common Infrastructure
- Exception types + GlobalExceptionHandler (@RestControllerAdvice)
- Base64UrlUtil, SecureIdGenerator
- **Verify**: Unit tests pass

### Step 3: Crypto Module
- KeyGenerationService, EncryptionService, MasterKeyService (Local + KMS)
- **Verify**: Generate key -> encrypt -> decrypt round-trip. JWE headers correct.

### Step 4: Audit Module
- AuditLogDocument, AuditAction, AuditOutcome, AuditLogRepository (imperative), AuditService
- **Verify**: Integration test with Testcontainers MongoDB: log event -> retrieve

### Step 5: S3 Payload Service
- S3PayloadService: upload/retrieve JWE blobs (using S3Client with Apache5HttpClient)
- **Verify**: Upload bytes -> retrieve bytes round-trip

### Step 6: FHIR + HealthLake
- FhirSerializationService (FhirContext.forR4(), Jackson 2 isolation)
- FhirBundleService, HealthLakeClient (RestClient + SigV4)
- HealthDataController + DTOs
- **Verify**: Build PatientSharedBundle -> POST to HealthLake -> GET back

### Step 7: SHL Core (Creation)
- ShlDocument, ShlService, ShlManagementController
- **Verify**: Upload data -> create SHL -> verify SHLink URL format, JWE in S3

### Step 8: SHL Retrieval (Public Endpoint)
- ShlRetrievalService, ShlController (public GET)
- **Verify**: E2E: create SHL -> GET /shl/{id}?recipient=Test -> decrypt -> verify FHIR JSON

### Step 9: Audit Viewing
- AuditController + SHL-specific audit endpoints
- **Verify**: After SHL access, patient sees audit trail

### Step 10: GraphQL Layer
- schema.graphqls + GraphQL controllers (@BatchMapping) + type mappings
- GraphQL instrumentation beans (MaxQueryComplexity, MaxQueryDepth)
- **Verify**: GraphQL queries return FHIR data from HealthLake, pagination works

### Step 11: Rate Limiting + Observability
- Bucket4j rate limiting on public SHL endpoints
- OTLP configuration + @Observed on key operations
- **Verify**: Rate limits enforced, metrics exported

### Step 12: Integration Tests + Phase 2 Stubs
- Full E2E flow tests with Testcontainers 2.0.3 (testcontainers-mongodb)
- Error cases, security boundary tests
- Phase 2 POST endpoint returns 501, Phase 2 DTOs defined
- **Verify**: All tests pass, @ServiceConnection works

---

## Verification Plan

1. **Unit tests**: Crypto round-trip, FHIR bundle construction (Jackson 2 path), base64url encoding
2. **Integration tests**: MongoDB CRUD (imperative), S3 upload/download, HealthLake FHIR API
3. **E2E test**: Upload health data -> create SHL -> GET /shl/{id} -> decrypt -> verify FHIR
4. **Security test**: /shl/** public, /api/** requires OAuth2, /graphql requires auth
5. **Audit test**: Every action produces audit log entry in MongoDB
6. **GraphQL test**: @GraphQlTest slice tests, @BatchMapping N+1 prevention, pagination
7. **Virtual thread test**: Verify no carrier thread pinning (`-Djdk.tracePinnedThreads=full`)
8. **Rate limit test**: Bucket4j enforces limits on SHL retrieval endpoint
