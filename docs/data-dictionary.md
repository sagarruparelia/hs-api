# Data Dictionary

Single source of truth for all field names, types, and enumerations used across the hs-api system.

---

## MongoDB Collections

### `shl_links` — SHL Link Metadata

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Auto | MongoDB document ID (Base64URL-encoded random bytes) |
| `enterpriseId` | String | Yes | Enterprise patient identifier (scopes all queries) |
| `label` | String | No | User-friendly link description |
| `mode` | String | Yes | `"snapshot"` or `"live"` — determines retrieval behavior |
| `flag` | String | Yes | HL7 SHL flag: `"U"` (snapshot/direct file) or `"L"` (live/manifest) |
| `encryptionKey` | String | Yes | AES-256 key, encrypted at rest with `ENCRYPTION_KEY` (AES-GCM) |
| `selectedResources` | List\<String\> | Yes | FHIR resource types to include (see [FhirResourceType](#fhirresourcetype)) |
| `includePdf` | boolean | Yes | Whether to generate a PDF/UA patient summary |
| `patientName` | String | No | Patient name for PDF generation |
| `expiresAt` | Instant | Yes | Link expiration (ISO-8601). Range: 5 minutes to 365 days from creation |
| `status` | String | Yes | `"active"` or `"revoked"` (stored lowercase) |
| `s3Key` | String | Snapshot only | S3 object key for pre-built JWE payload. `null` for live mode |
| `createdAt` | Instant | Yes | Creation timestamp |
| `accessHistory` | List\<AccessRecord\> | Yes | Last 50 access audit entries (capped on write) |

**Computed field:**

| Field | Derivation |
|-------|-----------|
| `effectiveStatus` | Returns `"revoked"` if `status == "revoked"`, `"expired"` if `expiresAt < now`, otherwise `"active"` |

**Indexes:**
- `idx_enterpriseId_status` — compound on (`enterpriseId`, `status`)
- `idx_expiresAt` — single field on `expiresAt`

---

### `shl_audit_log` — SHL Action Audit Trail

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Auto | MongoDB document ID |
| `linkId` | String | Yes | Reference to `shl_links.id` |
| `enterpriseId` | String | Yes | Enterprise patient identifier |
| `action` | ShlAuditAction | Yes | Audit action enum value (see [ShlAuditAction](#shlauditaction)) |
| `recipient` | String | No | Who accessed the link (from request body or query param) |
| `detail` | Map\<String, Object\> | No | Arbitrary metadata (e.g., `reason`, `contentHash`, `flag`) |
| `ipAddress` | String | No | Client IP address |
| `userAgent` | String | No | HTTP User-Agent header |
| `requestId` | String | No | X-Request-Id correlation ID |
| `consumerId` | String | No | OAuth2 consumer ID from X-Consumer-Id header |
| `source` | String | No | `"external"` or internal source identifier |
| `timestamp` | Instant | Yes | When the action occurred |

**Indexes:**
- `idx_linkId_timestamp` — compound on (`linkId`, `timestamp` DESC)
- `idx_enterpriseId_timestamp` — compound on (`enterpriseId`, `timestamp` DESC)

---

### `audit_log` — General FHIR Query Audit Trail

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Auto | MongoDB document ID |
| `enterpriseId` | String | Yes | Enterprise patient identifier |
| `action` | String | Yes | Action name (e.g., `"QUERY"`) |
| `resourceType` | FhirResourceType | No | FHIR resource type accessed |
| `resourceId` | String | No | Specific resource ID if applicable |
| `detail` | Map\<String, Object\> | No | Arbitrary audit metadata |
| `ipAddress` | String | No | Client IP address |
| `userAgent` | String | No | HTTP User-Agent header |
| `requestId` | String | No | X-Request-Id correlation ID |
| `consumerId` | String | No | OAuth2 consumer ID |
| `source` | String | No | Source identifier |
| `timestamp` | Instant | Yes | When the action occurred |

**Indexes:**
- `idx_enterpriseId_timestamp` — compound on (`enterpriseId`, `timestamp` DESC)

---

### `patient_crosswalk` — Enterprise-to-HealthLake ID Mapping

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Set to `enterpriseId` value |
| `enterpriseId` | String | Yes | Enterprise patient identifier (unique) |
| `healthLakePatientId` | String | Yes | Corresponding AWS HealthLake FHIR Patient resource ID |

**Indexes:**
- `idx_enterpriseId_unique` — unique index on `enterpriseId`

---

## Embedded Types

### AccessRecord

Embedded in `shl_links.accessHistory`. Capped at 50 entries per link.

| Field | Type | Description |
|-------|------|-------------|
| `recipient` | String | Who accessed the link |
| `action` | String | `"ACCESSED"`, `"ACCESS_REVOKED"`, or `"ACCESS_EXPIRED"` |
| `timestamp` | Instant | When the access occurred |

---

## Enumerations

### ShlAuditAction

Actions logged to `shl_audit_log`:

| Value | Description |
|-------|-------------|
| `LINK_CREATED` | New SHL link created |
| `LINK_REVOKED` | SHL link revoked by authenticated user |
| `LINK_ACCESSED` | SHL link successfully accessed (public endpoint) |
| `LINK_ACCESS_EXPIRED` | Access attempted on expired link |
| `LINK_ACCESS_REVOKED` | Access attempted on revoked link |
| `LINK_DENIED` | Access denied (wrong flag, not found, etc.) |

### FhirResourceType

Supported FHIR R4 resource types. Used in `selectedResources` and audit logging:

| Value | FHIR R4 Resource | GraphQL Query |
|-------|------------------|---------------|
| `Patient` | Patient | `patientSummary` |
| `MedicationRequest` | MedicationRequest | `medications` |
| `Immunization` | Immunization | `immunizations` |
| `AllergyIntolerance` | AllergyIntolerance | `allergies` |
| `Condition` | Condition | `conditions` |
| `Procedure` | Procedure | `procedures` |
| `Observation` | Observation | `labResults` |
| `Coverage` | Coverage | `coverages` |
| `ExplanationOfBenefit` | ExplanationOfBenefit | `claims` |
| `Appointment` | Appointment | `appointments` |
| `CareTeam` | CareTeam | `careTeams` |

### ShlMode

| Value | Description |
|-------|-------------|
| `SNAPSHOT` | Bundle pre-built and stored in S3 at link creation. Retrieved via GET |
| `LIVE` | Bundle built on-demand from HealthLake on each access. Retrieved via POST manifest |

### ShlStatus

| Value | Description |
|-------|-------------|
| `ACTIVE` | Link is usable (may still be expired — check `effectiveStatus`) |
| `REVOKED` | Link explicitly revoked by authenticated user |

### IdType

| Value | Description |
|-------|-------------|
| `ENTERPRISE_ID` | Enterprise patient identifier. Only supported ID type |

### ShlFlag

| Value | Mode | HTTP Method | Description |
|-------|------|-------------|-------------|
| `U` | Snapshot | GET | Direct file delivery — returns JWE payload |
| `L` | Live | POST | Manifest-based delivery — returns manifest with embedded JWE |

---

## S3 Object Schema

| Property | Value |
|----------|-------|
| **Key pattern** | `shl/{enterpriseId}/{linkId}.jwe` |
| **Content type** | `application/jose` |
| **Content** | JWE compact serialization (5-part, dot-separated) |
| **Encryption** | `alg: dir`, `enc: A256GCM`, `zip: DEF`, `cty: application/fhir+json` |

Example key: `shl/acme-health/aB3xY9z2kL.jwe`

---

## SHLink Payload Schema

The SHLink URI (`shlink:/{base64url-payload}`) encodes a JSON payload:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `url` | String | Yes | Full URL to SHL endpoint: `{baseUrl}/shl/{linkId}` |
| `flag` | String | Yes | `"U"` (snapshot) or `"L"` (live) |
| `key` | String | Yes | Base64URL-encoded raw 32-byte AES-256 key (no padding) |
| `exp` | Number | Yes | Expiration as Unix epoch seconds |
| `label` | String | No | Human-readable link description |

Example decoded payload:
```json
{
  "url": "https://api.example.com/shl/aB3xY9z2kL",
  "flag": "U",
  "key": "5la3QVnVeiMI-cdf_DBFdtzBZG1ESauJ_ElGuYYU",
  "exp": 1741305600,
  "label": "Patient Health Summary"
}
```

---

## REST API DTOs

### Request Types

| DTO | Fields |
|-----|--------|
| **ShlCreateRequest** | `idType` (String), `idValue` (String), `label` (String), `expiresAt` (String, ISO-8601), `selectedResources` (List\<String\>), `includePdf` (boolean), `patientName` (String), `mode` (String) |
| **ShlSearchRequest** | `idType` (String), `idValue` (String), `linkId` (String, optional) |
| **ShlRevokeRequest** | `idType` (String), `idValue` (String), `linkId` (String) |

### Response Types

| DTO | Fields |
|-----|--------|
| **ShlCreateResponse** | `linkId` (String), `shlinkUrl` (String), `expiresAt` (Instant) |
| **ShlLinkResponse** | `linkId`, `label`, `mode`, `flag`, `effectiveStatus`, `shlinkUrl`, `expiresAt`, `createdAt`, `selectedResources`, `includePdf`, `accessHistory` |
| **ManifestResponse** | `status` (String: `"can-change"` or `"no-longer-valid"`), `files` (List\<ManifestFile\>) |
| **ManifestFile** | `contentType` (String: `"application/fhir+json"`), `embedded` (String: JWE compact form) |
| **ErrorResponse** | `error` (String), `message` (String), `timestamp` (Instant), `path` (String) |
