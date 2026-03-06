# QE Testing Guide — hs-api

This guide covers test environments, data setup, manual testing flows, edge cases,
security verification, and automated test structure for the hs-api service.

hs-api is a Spring Boot 4.0.3 / Java 25 backend exposing GraphQL and SHL (SMART
Health Links) REST APIs for health data sharing. It uses MongoDB for persistence,
AWS HealthLake for FHIR R4 clinical data, and S3 for encrypted snapshot storage.

---

## Table of Contents

1. [Test Environments](#1-test-environments)
2. [Test Data Setup](#2-test-data-setup)
3. [API Surface Quick Reference](#3-api-surface-quick-reference)
4. [Manual Testing Flows](#4-manual-testing-flows)
5. [What to Verify Per Feature](#5-what-to-verify-per-feature)
6. [Edge Cases and Negative Tests](#6-edge-cases-and-negative-tests)
7. [Security Testing Checklist](#7-security-testing-checklist)
8. [Automated Test Structure](#8-automated-test-structure)
9. [Audit Log Verification](#9-audit-log-verification)
10. [JWE Header Compliance Checks](#10-jwe-header-compliance-checks)

---

## 1. Test Environments

### Local

Start MongoDB via Docker Compose and run the application with the `dev` profile:

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The application starts on port **8080** (`http://localhost:8080`).

### Integration

The integration environment uses the same local MongoDB instance but connects to
live AWS services:

- **HealthLake**: FHIR R4 data store in `ap-south-1`
- **S3**: Bucket `healthsafe-shl-ap-south-1` for encrypted SHL snapshots

### Prerequisites

Before running tests in either environment, ensure:

- **Docker** is running (required for MongoDB via Compose and for Testcontainers)
- **AWS credentials** are configured — either through the AWS CLI (`aws configure`)
  or environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
  `AWS_REGION=ap-south-1`)
- **Java 25** is installed and `JAVA_HOME` points to the JDK 25 installation
- The Maven wrapper (`./mvnw`) is present in the project root

### Verifying the Environment

After starting the application, confirm it is healthy:

```bash
curl -s http://localhost:8080/health
```

Expected response: HTTP 200 with a JSON body indicating `UP` status.

---

## 2. Test Data Setup

### Patient Crosswalk

The MongoDB `patient_crosswalk` collection maps enterprise patient IDs to
HealthLake FHIR patient IDs. Insert test entries before running SHL or GraphQL
tests:

```json
{
  "enterpriseId": "test-patient-1",
  "healthLakePatientId": "abc-123-def"
}
```

Insert via `mongosh`:

```javascript
use hsapi;
db.patient_crosswalk.insertOne({
  enterpriseId: "test-patient-1",
  healthLakePatientId: "abc-123-def"
});
```

Verify the entry exists:

```javascript
db.patient_crosswalk.findOne({ enterpriseId: "test-patient-1" });
```

### HealthLake

The HealthLake data store in `ap-south-1` must contain FHIR R4 Patient resources
and associated clinical resources (Conditions, Medications, Observations, etc.)
for the `healthLakePatientId` values referenced in the crosswalk collection.

If no clinical data exists for a given patient, GraphQL queries will return empty
results and SHL bundles will contain only the Patient resource.

### SHL Data

No SHL seed data is needed. SHL links are created dynamically through the API
during testing. The `shl_links` and `shl_audit_log` collections are populated
as a side effect of API calls.

---

## 3. API Surface Quick Reference

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `/secure/api/v1/shl/search` | POST | X-Consumer-Id | Search SHL links for a patient |
| `/secure/api/v1/shl/get` | POST | X-Consumer-Id | Get a single SHL link by ID |
| `/secure/api/v1/shl/preview` | POST | X-Consumer-Id | Preview decrypted SHL content |
| `/secure/api/v1/shl/create` | POST | X-Consumer-Id | Create a new SHL link |
| `/secure/api/v1/shl/revoke` | POST | X-Consumer-Id | Revoke an existing SHL link |
| `/shl/{id}` | GET | None | Public snapshot retrieval (U-flag) |
| `/shl/{id}` | POST | None | Public manifest retrieval (L-flag) |
| `/graphql` | POST | X-Consumer-Id | GraphQL queries for clinical data |
| `/health` | GET | None | Health check |

All secured endpoints (`/secure/api/v1/**`) require the `X-Consumer-Id` header.
All secured endpoints use the POST method exclusively.

Public endpoints (`/shl/{id}`, `/health`) do not require any authentication headers.

---

## 4. Manual Testing Flows

For complete curl command examples, see
[e2e-curl-commands.md](./e2e-curl-commands.md).

### Happy Path -- Snapshot Flow

This flow exercises the full lifecycle of a snapshot-mode SHL link.

**Step 1: Create the link**

```
POST /secure/api/v1/shl/create
Header: X-Consumer-Id: test-consumer
Body: { mode: "SNAPSHOT", ... }
```

Response includes `linkId` and `shlinkUrl`. Save both.

**Step 2: Verify the link exists**

```
POST /secure/api/v1/shl/get
Header: X-Consumer-Id: test-consumer
Body: { linkId: "<linkId>" }
```

Verify `effectiveStatus` is `active`.

**Step 3: Decode the SHLink URL**

Parse `shlinkUrl` — it starts with `shlink:/`. After stripping the prefix,
Base64URL-decode the payload to extract a JSON object containing:

- `url` — the public retrieval endpoint
- `key` — the AES-256-GCM decryption key (Base64URL-encoded)
- `flag` — should be `U` for snapshot mode
- `exp` — expiration timestamp (Unix epoch seconds)
- `label` — human-readable label

**Step 4: Access the link publicly**

```
GET /shl/{id}?recipient=test
Accept: application/jose
```

Response body is a JWE (compact serialization, content type `application/jose`).

**Step 5: Decrypt the JWE**

Using the `key` from Step 3, decrypt the JWE. The plaintext is a DEFlate-compressed
FHIR R4 Bundle in JSON format. Verify the Bundle is valid JSON and contains
expected resources.

**Step 6: Check access history**

```
POST /secure/api/v1/shl/get
Header: X-Consumer-Id: test-consumer
Body: { linkId: "<linkId>" }
```

Verify `accessHistory` contains an entry with `recipient: "test"` and a recent
timestamp.

**Step 7: Revoke the link**

```
POST /secure/api/v1/shl/revoke
Header: X-Consumer-Id: test-consumer
Body: { linkId: "<linkId>" }
```

Expected response: HTTP 200 OK (empty body).

**Step 8: Verify the link is revoked**

```
POST /secure/api/v1/shl/get
Header: X-Consumer-Id: test-consumer
Body: { linkId: "<linkId>" }
```

Verify `effectiveStatus` is `revoked`.

**Step 9: Confirm public access is denied**

```
GET /shl/{id}?recipient=test
```

Expected response: HTTP 404. The audit log should contain an entry with
action `LINK_ACCESS_REVOKED`.

---

### Happy Path -- Live Flow

This flow exercises a live-mode SHL link, which generates fresh bundles on each
access.

**Step 1: Create the link**

```
POST /secure/api/v1/shl/create
Header: X-Consumer-Id: test-consumer
Body: { mode: "LIVE", ... }
```

Response includes `linkId` and `shlinkUrl`. Save both.

**Step 2: Decode the SHLink URL**

Parse `shlinkUrl` the same way as the snapshot flow. Verify `flag` is `L`.

**Step 3: Access the link publicly**

```
POST /shl/{id}
Content-Type: application/json
Body: {"recipient": "test"}
```

Response is a `ManifestResponse` JSON object with `status: "can-change"` and a
`files` array containing embedded JWE content.

**Step 4: Decrypt the JWE**

Extract the JWE from the manifest response `files` array, then decrypt using the
key from the SHLink URL. Verify the result is a valid FHIR R4 Bundle.

**Step 5: Access again and verify freshness**

Repeat Step 3. Compare the `contentHash` in the audit log entries for the two
accesses. Since live mode generates a fresh bundle each time, the content hash
may differ if underlying clinical data has changed.

---

### GraphQL Flow

**Step 1: Query resource counts**

```graphql
query {
  resourceCounts(enterpriseId: "test-patient-1") {
    conditions
    medications
    labResults
    allergies
    immunizations
    procedures
    total
  }
}
```

Verify all count fields are non-negative integers.

**Step 2: Query patient summary**

```graphql
query {
  patientSummary(enterpriseId: "test-patient-1") {
    firstName
    lastName
    birthDate
    gender
  }
}
```

Verify demographic fields match the HealthLake Patient resource.

**Step 3: Query medications with date filters**

```graphql
query {
  medications(
    enterpriseId: "test-patient-1"
    startDate: "2024-01-01"
    endDate: "2025-12-31"
  ) {
    name
    status
    dosage
  }
}
```

Verify only medications within the date range are returned.

**Step 4: Query health dashboard**

```graphql
query {
  healthDashboard(enterpriseId: "test-patient-1") {
    patientSummary { firstName lastName }
    resourceCounts { conditions medications total }
  }
}
```

Verify the combined response includes both patient summary and resource counts.

---

## 5. What to Verify Per Feature

### SHL Create

- [ ] `linkId` is returned and is a unique identifier
- [ ] `shlinkUrl` starts with `shlink:/` and Base64URL-decodes to valid JSON
- [ ] Decoded JSON contains: `url`, `flag`, `key`, `exp`, `label`
- [ ] `flag` is `U` for SNAPSHOT mode, `L` for LIVE mode
- [ ] `expiresAt` in the response matches the requested expiration value
- [ ] For SNAPSHOT: an S3 object exists at key `shl/{enterpriseId}/{linkId}.jwe`
- [ ] For LIVE: no S3 object is created at creation time
- [ ] Audit log contains an entry with `action: "LINK_CREATED"`

### SHL Retrieval

- [ ] Snapshot (GET): response content type is `application/jose`
- [ ] Live (POST): response is a `ManifestResponse` JSON with a `files` array
- [ ] JWE header fields: `alg=dir`, `enc=A256GCM`, `zip=DEF`,
      `cty=application/fhir+json`
- [ ] Decrypted content is a valid FHIR R4 Bundle (JSON)
- [ ] An access record is added to the link's `accessHistory` array
- [ ] Audit log contains an entry with `action: "LINK_ACCESSED"`

### Access History

- [ ] Each public access adds an entry with `recipient`, `action`, and `timestamp`
- [ ] Access history is capped at 50 entries (oldest are dropped)
- [ ] Denied access attempts are also logged with actions:
      `ACCESS_REVOKED`, `ACCESS_EXPIRED`

### GraphQL

- [ ] All 13 query types return expected response shapes
- [ ] `startDate` / `endDate` filtering correctly narrows results
- [ ] `sortOrder` `ASC` / `DESC` produces correctly ordered results
- [ ] An invalid or non-existent `enterpriseId` returns empty results (not an error)

---

## 6. Edge Cases and Negative Tests

### Authentication

| Scenario | Expected |
|---|---|
| Missing `X-Consumer-Id` header on `/secure/**` | 401 `{"error":"unauthorized","message":"X-Consumer-Id header is required"}` |
| Empty `X-Consumer-Id` header on `/secure/**` | 401 `{"error":"unauthorized","message":"X-Consumer-Id header is required"}` |
| No auth header on `/shl/{id}` | Succeeds (public endpoint) |
| No auth header on `/health` | Succeeds (public endpoint) |

### Invalid Requests

| Scenario | Expected |
|---|---|
| Unknown path (e.g., `GET /api/foo`) | Denied by Spring Security `denyAll` rule |
| Invalid JSON body | 400 Bad Request |
| Missing required fields in request body | 400 Bad Request |
| Invalid `idType` (anything other than `ENTERPRISE_ID`) | 400 Bad Request |
| Non-existent `enterpriseId` | 404 or empty results depending on the endpoint |

### SHL-Specific Edge Cases

| Scenario | Expected |
|---|---|
| GET on an L-flag (live mode) link | 400 — wrong HTTP method for live mode |
| POST on a U-flag (snapshot mode) link | 400 — wrong HTTP method for snapshot mode |
| Access an expired link | 404 with audit action `LINK_ACCESS_EXPIRED` |
| Access a revoked link | 404 with audit action `LINK_ACCESS_REVOKED` |
| Revoke an already-revoked link | 404 |
| Create with `expiresAt` less than 5 minutes from now | 400 |
| Create with `expiresAt` more than 365 days from now | 400 |
| Create with empty `selectedResources` array | 400 |
| Create with invalid `mode` (not `SNAPSHOT` or `LIVE`) | 400 |

### GraphQL Edge Cases

| Scenario | Expected |
|---|---|
| Query exceeding max complexity (200) | GraphQL error response |
| Query exceeding max depth (10) | GraphQL error response |
| Invalid query syntax | GraphQL error response with parsing details |

---

## 7. Security Testing Checklist

### Response Headers

Verify the following headers are present on **every** response from the API:

- [ ] `X-Content-Type-Options: nosniff`
- [ ] `X-Frame-Options: DENY`
- [ ] `X-XSS-Protection: 0`
- [ ] `Referrer-Policy: strict-origin-when-cross-origin`
- [ ] `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
- [ ] `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- [ ] `Cache-Control: no-store`
- [ ] `Pragma: no-cache`

Quick verification with curl:

```bash
curl -sI http://localhost:8080/health | grep -iE \
  "x-content-type|x-frame|x-xss|referrer|content-security|strict-transport|cache-control|pragma"
```

### CORS

- [ ] `/shl/**`: allows all origins (`Access-Control-Allow-Origin: *`),
      methods `GET`, `POST`, `OPTIONS`
- [ ] `/secure/api/**`: only allows configured origins, method `POST` only
- [ ] Preflight `OPTIONS` requests return correct `Access-Control-Allow-*` headers

Test CORS preflight:

```bash
curl -sI -X OPTIONS http://localhost:8080/shl/test-id \
  -H "Origin: https://example.com" \
  -H "Access-Control-Request-Method: GET" \
  | grep -i "access-control"
```

### Authentication Boundary

- [ ] All `/secure/api/**` endpoints require `X-Consumer-Id` — requests without
      it receive 401
- [ ] `/shl/**` endpoints work without any authentication header
- [ ] `/health` works without any authentication header
- [ ] All other paths (not matching any defined route) are denied by the
      Spring Security `denyAll` catch-all rule

---

## 8. Automated Test Structure

The project has 12 test classes covering different aspects of the application.

| Test Class | Package | Focus |
|---|---|---|
| `HsApiApplicationTests` | root | Spring context loads successfully |
| `ShlCreateFlowTest` | shl | Snapshot vs live creation, S3 key format, encryption, audit |
| `JweComplianceTest` | crypto | JWE format validation (dir, A256GCM, DEF), round-trip |
| `CryptoRoundTripTest` | crypto | Field encryption AES-GCM, key generation |
| `ManifestRetrievalTest` | shl | Live mode manifest generation and response shape |
| `SnapshotRetrievalTest` | shl | Snapshot mode S3 retrieval and decryption |
| `SecurityBoundaryTest` | shl | Wrong-flag access, revoked/expired denial, audit entries |
| `ExpiryValidationTest` | shl | Expiry boundaries (minimum 5 min, maximum 365 days) |
| `PshdBundleComplianceTest` | shl | FHIR R4 PSHD bundle structure and content |
| `ShlinkUriComplianceTest` | shl | SHLink URI format, Base64URL encoding correctness |
| `PatientCrosswalkIntegrationTest` | crosswalk | Enterprise-to-HealthLake ID mapping |
| `ShlCreateFlow` | shl | Additional creation flow scenarios |

### Running Tests

Run the full test suite:

```bash
./mvnw test
```

Run a specific test class:

```bash
./mvnw test -Dtest=JweComplianceTest
```

Run a specific test method:

```bash
./mvnw test -Dtest=JweComplianceTest#testJweHeaderCompliance
```

Run tests in a specific package:

```bash
./mvnw test -Dtest="com.chanakya.hsapi.shl.*"
```

### Test Infrastructure Notes

- Tests use **Testcontainers 2.0.3** for MongoDB — no external MongoDB instance
  is needed for unit/integration tests
- Testcontainers requires Docker to be running
- The `@ServiceConnection` annotation auto-configures the MongoDB connection
  for test containers (requires `spring-boot-testcontainers` dependency)
- AWS service calls (HealthLake, S3) are mocked in unit tests
- Integration tests that hit real AWS services are profiled separately

---

## 9. Audit Log Verification

The `shl_audit_log` collection in MongoDB records all significant operations on
SHL links. After performing any operation, query this collection to verify the
audit trail.

### Querying Audit Logs

Connect to MongoDB via `mongosh` and run:

```javascript
use hsapi;

// Find all audit entries for a specific link, newest first
db.shl_audit_log.find({ linkId: "abc123" }).sort({ timestamp: -1 });

// Find all entries for a specific enterprise ID
db.shl_audit_log.find({ enterpriseId: "test-patient-1" }).sort({ timestamp: -1 });

// Find entries by action type
db.shl_audit_log.find({ action: "LINK_ACCESSED" }).sort({ timestamp: -1 });
```

### Expected Actions Per Operation

| Operation | Audit Action |
|---|---|
| Create a new SHL link | `LINK_CREATED` |
| GET snapshot (public) | `LINK_ACCESSED` (includes `contentHash` in detail) |
| POST manifest (public) | `LINK_ACCESSED` |
| Revoke a link | `LINK_REVOKED` |
| Access an expired link | `LINK_ACCESS_EXPIRED` |
| Access a revoked link | `LINK_ACCESS_REVOKED` |
| Wrong HTTP method for flag | `LINK_DENIED` |

### Required Fields in Every Audit Entry

Every audit log document must contain all of the following fields:

| Field | Description |
|---|---|
| `linkId` | The SHL link identifier |
| `enterpriseId` | The enterprise patient identifier |
| `action` | One of the action values listed above |
| `ipAddress` | IP address of the requester |
| `userAgent` | User-Agent header from the request |
| `requestId` | Unique request identifier for correlation |
| `timestamp` | ISO 8601 timestamp of the event |

### Verification Example

After creating a link and accessing it once:

```javascript
db.shl_audit_log.find({ linkId: "<linkId>" }).sort({ timestamp: 1 }).forEach(
  function(doc) {
    print(doc.action + " at " + doc.timestamp + " from " + doc.ipAddress);
  }
);
```

Expected output:

```
LINK_CREATED at 2026-03-06T10:00:00Z from 127.0.0.1
LINK_ACCESSED at 2026-03-06T10:01:00Z from 127.0.0.1
```

---

## 10. JWE Header Compliance Checks

SHL links return encrypted content as JWE (JSON Web Encryption) in compact
serialization format. This section describes how to verify JWE compliance.

### JWE Structure

A JWE compact serialization consists of five Base64URL-encoded parts separated
by periods:

```
<header>.<encryptedKey>.<iv>.<ciphertext>.<tag>
```

### Verification Steps

**Step 1: Retrieve the JWE**

For snapshot links:

```bash
JWE=$(curl -s http://localhost:8080/shl/{id}?recipient=test \
  -H "Accept: application/jose")
```

For live links, extract the JWE from the manifest response `files` array.

**Step 2: Split and count parts**

```bash
echo "$JWE" | tr '.' '\n' | wc -l
# Expected: 5
```

**Step 3: Decode and verify the header**

```bash
echo "$JWE" | cut -d. -f1 | base64 -d 2>/dev/null
```

Expected header JSON:

```json
{
  "alg": "dir",
  "enc": "A256GCM",
  "zip": "DEF",
  "cty": "application/fhir+json"
}
```

Field explanations:

| Field | Value | Meaning |
|---|---|---|
| `alg` | `dir` | Direct key agreement (no key wrapping) |
| `enc` | `A256GCM` | AES-256-GCM content encryption |
| `zip` | `DEF` | DEFLATE compression applied before encryption |
| `cty` | `application/fhir+json` | Content type of the decrypted payload |

**Step 4: Verify empty encrypted key**

The second part (encrypted key) must be empty because `alg=dir` means the
content encryption key is used directly:

```bash
ENCRYPTED_KEY=$(echo "$JWE" | cut -d. -f2)
[ -z "$ENCRYPTED_KEY" ] && echo "PASS: encrypted key is empty" \
  || echo "FAIL: encrypted key should be empty for alg=dir"
```

**Step 5: Decrypt and verify content**

Using the key from the SHLink URL (Base64URL-decoded to 32 bytes):

1. Base64URL-decode the IV (part 3), ciphertext (part 4), and tag (part 5)
2. Decrypt using AES-256-GCM with the key and IV
3. INFLATE (decompress) the plaintext
4. Parse as JSON and verify it is a valid FHIR R4 Bundle

The decrypted Bundle should:

- Have `resourceType: "Bundle"`
- Have `type: "collection"`
- Contain `entry` array with FHIR R4 resources
- Include at minimum a Patient resource

---

## Appendix: Common Troubleshooting

### Application fails to start

- Verify Docker is running (`docker ps`)
- Verify MongoDB container is up (`docker compose ps`)
- Check AWS credentials are configured (`aws sts get-caller-identity`)
- Ensure port 8080 is not in use (`lsof -i :8080`)

### Tests fail with container errors

- Ensure Docker daemon is running
- Check available disk space for container images
- Verify Testcontainers can connect to Docker (`docker info`)

### GraphQL queries return empty results

- Verify the `patient_crosswalk` collection has the test enterprise ID
- Verify HealthLake has clinical data for the mapped patient ID
- Check the application logs for HealthLake API errors

### SHL access returns 404 unexpectedly

- Check if the link has expired (`expiresAt` in the past)
- Check if the link has been revoked (`effectiveStatus: "revoked"`)
- Verify you are using the correct HTTP method for the flag type
  (GET for U-flag, POST for L-flag)

### JWE decryption fails

- Ensure you are using the key from the decoded SHLink URL, not the linkId
- The key must be Base64URL-decoded to raw bytes before use
- Verify AES-256-GCM parameters: 256-bit key, 96-bit IV, 128-bit tag
