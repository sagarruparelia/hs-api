# Smart Health Links (SHL) - Architecture & Implementation Plan

## Context

Build a **Patient App** that generates SMART Health Links (SHL) enabling patients to share encrypted FHIR health data with providers via QR codes. The app implements the Patient-Shared Health Document via SHL spec (must-have), with a foundation extensible to the full SHL protocol (long-term).

**Current state**: Empty Spring Boot 4.0.3 + Java 25 skeleton with WebFlux + Reactive MongoDB + OAuth2 Client + Validation + Actuator. Package: `com.chanakya.hsapi`.

**Spec sources**:
- Must-have: hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/rkGeS5cIZe
- Must-have: hackmd.io/@Jyncr3iQS1iJA09xcuh7QA/SJHzQ1aDbx
- Long-term: build.fhir.org/ig/HL7/smart-health-cards-and-links/links-specification.html

---

## Infrastructure Decisions

| Concern | Technology | Role |
|---------|-----------|------|
| **FHIR data store** | AWS HealthLake (FHIR R4) | Authoritative store for Patient, DocumentReference, Bundles, all FHIR resources |
| **Transactional DB** | MongoDB (reactive) | SHL metadata, audit logs, app state |
| **SHL payload storage** | AWS S3 | Encrypted JWE blobs (the actual shared payloads) |
| **GraphQL** | Spring for GraphQL (WebFlux) | Query layer over FHIR resources from HealthLake |
| **API framework** | WebFlux (reactive) | REST endpoints (SHL protocol) + GraphQL |

---

## Skills to Install (Before Implementation)

| Skill | Source | Purpose |
|-------|--------|---------|
| `fhir-developer` | `github.com/TopologyHealth/ClaudeFHIRSkill` | FHIR R4 resource construction, validation, HL7 compliance |
| `aws-skills` | `github.com/zxkane/aws-skills` | AWS SDK patterns, S3, IAM best practices |

---

## Package Structure

```
com.chanakya.hsapi/
  HsApiApplication.java

  config/
    SecurityConfig.java           # Public /shl/** vs authenticated /api/**
    MongoConfig.java              # Reactive MongoDB, programmatic indexes
    AwsConfig.java                # S3AsyncClient, HealthLake WebClient beans
    GraphQlConfig.java            # Spring GraphQL + WebFlux wiring
    CryptoConfig.java             # Master key bean from env/secrets
    WebFluxConfig.java            # CORS, content negotiation

  shl/                            # Smart Health Link domain
    ShlRouter.java                # RouterFunction: public GET/POST /shl/{id}
    ShlHandler.java               # Protocol handlers (JWE retrieval, manifest)
    ShlManagementController.java  # RestController: /api/v1/shl/** (authenticated)
    model/
      ShlDocument.java            # Document - SHL metadata in MongoDB
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
      ShlRepository.java          # ReactiveCrudRepository

  crypto/                         # Encryption domain
    EncryptionService.java        # JWE: alg=dir, enc=A256GCM (Nimbus JOSE)
    KeyGenerationService.java     # 32-byte AES key gen + base64url
    MasterKeyService.java         # Interface for key encryption at rest
    LocalMasterKeyService.java    # Dev profile: env var AES key
    KmsMasterKeyService.java      # Prod profile: AWS KMS

  fhir/                           # FHIR + HealthLake domain
    FhirBundleService.java        # Build PatientSharedBundle (HAPI FHIR R4)
    FhirSerializationService.java # FhirContext singleton, JSON parse/serialize
    HealthLakeClient.java         # WebClient-based FHIR REST client to HealthLake
    dto/
      UploadHealthDataRequest.java
      PatientInfo.java
      DocumentInfo.java
      HealthDataSummary.java
    controller/
      HealthDataController.java   # RestController: /api/v1/health-data/**

  graphql/                        # GraphQL layer over FHIR
    PatientGraphQlController.java
    DocumentReferenceGraphQlController.java
    BundleGraphQlController.java
    type/                         # GraphQL Java types mapping FHIR resources
      PatientType.java
      DocumentReferenceType.java
      BundleType.java

  storage/                        # S3 storage for JWE payloads
    S3PayloadService.java         # Upload/retrieve encrypted JWE from S3

  audit/                          # Audit logging domain
    AuditService.java             # Fire-and-forget reactive audit logging
    AuditController.java          # RestController: /api/v1/audit/**
    model/
      AuditLogDocument.java       # Document - every interaction in MongoDB
      AuditAction.java            # SHL_CREATED, SHL_ACCESSED, etc.
      AuditOutcome.java           # SUCCESS, FAILURE, DENIED
    dto/
      AuditLogEntry.java          # Record: patient-facing audit entry
    repository/
      AuditLogRepository.java

  common/
    exception/
      GlobalExceptionHandler.java
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

### MongoDB Collections (Transactional Data)

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

- Stores all FHIR R4 resources: Patient, DocumentReference, Bundle, Conditions, Medications, Observations
- Accessed via FHIR REST API (HealthLake exposes standard FHIR endpoints)
- HealthLakeClient uses WebClient with AWS SigV4 signing
- CRUD: POST /Patient, GET /Patient/{id}, POST /Bundle, GET /Bundle/{id}
- HealthLake handles FHIR validation, indexing, and search

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
  1. Build FHIR R4 PatientSharedBundle via FhirBundleService (HAPI FHIR)
     - Patient resource with demographics
     - DocumentReference: status=current, type=LOINC 60591-5,
       category=patient-shared, embedded PDF
     - Additional FHIR resources
  2. POST Bundle to HealthLake via HealthLakeClient
  3. HealthLake validates, indexes, returns resource ID
  4. Log HEALTH_DATA_UPLOADED audit event
  5. Return HealthDataSummary with healthLakeResourceId
```

### Flow 2: Create SHL
```
Patient App -> POST /api/v1/shl (CreateShlRequest)
  1. Fetch FHIR Bundle from HealthLake by healthLakeResourceId
  2. Serialize Bundle to FHIR JSON via FhirSerializationService
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
  4. Returns structured data
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
| POST /graphql | Patient, DocumentReference, Bundle queries |

---

## Dependencies to Add

**Encryption:**
- `com.nimbusds:nimbus-jose-jwt:10.8` - JWE with AES-256-GCM

**FHIR R4 (Bundle construction only):**
- `ca.uhn.hapi.fhir:hapi-fhir-base:8.6.0` (exclude Jetty)
- `ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.6.0` (exclude Jetty)

**AWS SDK v2 (async):**
- `software.amazon.awssdk:bom:2.31.x` (in dependencyManagement)
- `software.amazon.awssdk:s3`
- `software.amazon.awssdk:netty-nio-client`
- `software.amazon.awssdk:healthlake`
- `software.amazon.awssdk:kms` (prod profile: master key management)

**GraphQL:**
- `org.springframework.boot:spring-boot-starter-graphql`
- `org.springframework.boot:spring-boot-starter-graphql-test` (test scope)

**Remove:**
- `org.projectlombok:lombok` (use records + explicit code)
- Lombok from maven-compiler-plugin annotationProcessorPaths

---

## GraphQL Schema

File: `src/main/resources/graphql/schema.graphqls`

```graphql
type Query {
    patient(id: ID!): Patient
    patients: [Patient!]!
    documentReferences(patientId: ID!): [DocumentReference!]!
    bundle(id: ID!): Bundle
}

type Patient {
    id: ID!
    name: [HumanName!]
    birthDate: String
    gender: String
    identifier: [Identifier!]
}

type HumanName { family: String, given: [String!] }
type Identifier { system: String, value: String }

type DocumentReference {
    id: ID!
    status: String!
    type: CodeableConcept
    category: [CodeableConcept!]
    date: String
    content: [DocumentContent!]
}

type CodeableConcept { coding: [Coding!], text: String }
type Coding { system: String, code: String, display: String }
type DocumentContent { attachment: Attachment }
type Attachment { contentType: String, title: String, size: Int }

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

1. **Pre-encrypt at SHL creation, store JWE in S3** - Retrieval is fast (S3 fetch + return), no crypto on hot path. S3 provides durable blob storage.

2. **HealthLake for FHIR, not MongoDB** - HealthLake handles FHIR validation, indexing, NLP, and compliance. MongoDB shouldn't parse FHIR.

3. **MongoDB for metadata + audit only** - Reactive MongoDB driver fits WebFlux. SHL metadata is transactional. Audit needs fast writes.

4. **Spring for GraphQL over DGS** - Official Spring integration, works natively with WebFlux, first-class Spring Boot 4 support.

5. **WebClient for HealthLake FHIR API** - HealthLake exposes FHIR REST endpoints. WebClient is reactive and supports AWS SigV4.

6. **HAPI FHIR for Bundle construction only** - Build FHIR resources in-memory, serialize to JSON, POST to HealthLake. Not for HTTP serving.

7. **Wrap blocking ops in boundedElastic** - HAPI FHIR, JCE crypto are blocking; must not run on event loop threads.

8. **256-bit secure random SHL IDs** - SHL spec requires 256+ bits of entropy in the URL.

9. **No Lombok** - Spring Boot 4 best practice: use Java records for DTOs, explicit code for entities.

10. **Dual key management** - Environment variable master key for local dev (simple base64 AES-256 key), AWS KMS for staging/prod (via Spring profiles). MasterKeyService abstracts the difference.

11. **QR code generation is client-side** - Server returns shlinkUrl and base64url-encoded payload. The UI application generates QR codes from the SHLink URL. No ZXing dependency needed server-side.

---

## Phase 2 Extension Points (Built into Phase 1, Not Implemented)

| Feature | Extension Point |
|---------|----------------|
| Manifest POST | ShlHandler.handleManifestRetrieval() returns 501 stub |
| Passcode (P flag) | ShlDocument.passcodeHash, remainingPasscodeAttempts (nullable) |
| Long-term links (L flag) | healthLakeResourceId enables re-fetch + re-encrypt |
| Multiple content types | contentType field, extensible manifest file entries |
| File locations | S3 presigned URLs in ManifestResponse (max 1 hour) |
| Status tracking | ShlStatus enum extensible to FINALIZED, CAN_CHANGE, NO_LONGER_VALID |

---

## Output

Write the full architecture document to `docs/architecture.md` in the project root, containing all sections from this plan (infrastructure, package structure, data architecture, flows, endpoints, dependencies, GraphQL schema, security, decisions, phase 2 extensions, build sequence).

---

## Build Sequence

### Step 0: Write Architecture Document + Install Skills
- Write `docs/architecture.md` with full architecture documentation
- Install fhir-developer and aws-skills Claude Code skills
- Clone and install `TopologyHealth/ClaudeFHIRSkill` to `.claude/skills/fhir-developer/`
- Clone and install `zxkane/aws-skills` to `.claude/skills/aws-skills/`
- **Verify**: Skills appear in skill list

### Step 1: Project Foundation
- Modify pom.xml: add all dependencies, remove Lombok, add AWS BOM, add start-class property
- Expand application.yaml: MongoDB, AWS (existing HealthLake endpoint), OAuth2, crypto, SHL config
- Create config classes: SecurityConfig, MongoConfig, AwsConfig, CryptoConfig (with profile-based key management)
- **Verify**: App starts, connects to MongoDB, S3 client initializes

### Step 2: Common Infrastructure
- exception types + GlobalExceptionHandler
- Base64UrlUtil, SecureIdGenerator
- **Verify**: Unit tests pass

### Step 3: Crypto Module
- KeyGenerationService, EncryptionService, MasterKeyService
- **Verify**: Generate key -> encrypt -> decrypt round-trip. JWE headers correct.

### Step 4: Audit Module
- AuditLogDocument, AuditAction, AuditOutcome, AuditLogRepository, AuditService
- **Verify**: Integration test: log event -> retrieve by shlId and patientId

### Step 5: S3 Payload Service
- S3PayloadService: upload/retrieve JWE blobs
- **Verify**: Upload bytes -> retrieve bytes round-trip

### Step 6: FHIR + HealthLake
- FhirSerializationService, FhirBundleService, HealthLakeClient
- HealthDataController + DTOs
- **Verify**: Build PatientSharedBundle -> POST to HealthLake -> GET back

### Step 7: SHL Core (Creation)
- ShlDocument, ShlService, ShlManagementController
- **Verify**: Upload data -> create SHL -> verify SHLink URL format, JWE in S3

### Step 8: SHL Retrieval (Public Endpoint)
- ShlRetrievalService, ShlHandler, ShlRouter
- **Verify**: E2E: create SHL -> GET /shl/{id}?recipient=Test -> decrypt -> verify FHIR JSON

### Step 9: Audit Viewing
- AuditController + wire into ShlManagementController
- **Verify**: After SHL access, patient sees audit trail

### Step 10: GraphQL Layer
- schema.graphqls + GraphQL controllers + type mappings
- **Verify**: GraphQL queries return FHIR data from HealthLake

### Step 11: Integration Tests + Phase 2 Stubs
- Full E2E flow tests, error cases, security boundary tests
- Phase 2 POST endpoint returns 501, Phase 2 DTOs defined

---

## Verification Plan

1. **Unit tests**: Crypto round-trip, FHIR bundle construction, base64url encoding
2. **Integration tests**: MongoDB CRUD, S3 upload/download, HealthLake FHIR API
3. **E2E test**: Upload health data -> create SHL -> GET /shl/{id} -> decrypt -> verify FHIR
4. **Security test**: /shl/** public, /api/** requires OAuth2, /graphql requires auth
5. **Audit test**: Every action produces audit log entry in MongoDB
6. **GraphQL test**: Query patient/document data from HealthLake
