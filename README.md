# hs-api

A secure backend service that enables patients and healthcare providers to access and share patient health data. Built around two primary functions: **Health Data Queries** via GraphQL across ten clinical data types, and **Smart Health Links (SHL)** for creating secure, time-limited, shareable links to patient health information.

## Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 25 LTS | Runtime |
| Spring Boot | 4.0.3 | Application framework |
| Spring for GraphQL | 2.0 | GraphQL API layer |
| HAPI FHIR | 8.8.0 | FHIR R4 resource handling |
| AWS HealthLake | R4 | Clinical data store |
| MongoDB | 7 | Application data (links, audit logs) |
| AWS S3 | — | Encrypted payload storage |
| Nimbus JOSE+JWT | — | JWE encryption for SHL payloads |

## Quick Start

**Prerequisites:** JDK 25, Docker, AWS CLI (configured), Maven

```bash
# 1. Start MongoDB
docker-compose up -d

# 2. Set required environment variables (see Configuration below)

# 3. Run the application
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**Access points:**
- API: `http://localhost:8080`
- GraphiQL: `http://localhost:8080/graphiql`
- Health check: `http://localhost:8080/actuator/health`

## API Overview

### GraphQL Queries

All queries require authentication via `POST /secure/api/v1/graphql` with `X-Consumer-Id` header.

| Query | Returns | Description |
|---|---|---|
| `medications` | `[Medication]` | Prescription and medication history |
| `immunizations` | `[Immunization]` | Vaccination records |
| `allergies` | `[Allergy]` | Allergy and intolerance data |
| `conditions` | `[Condition]` | Active and resolved diagnoses |
| `procedures` | `[Procedure]` | Surgical and clinical procedures |
| `labResults` | `[LabResult]` | Laboratory test results |
| `coverages` | `[Coverage]` | Insurance coverage information |
| `claims` | `[Claim]` | Insurance claims |
| `appointments` | `[Appointment]` | Scheduled appointments |
| `careTeams` | `[CareTeam]` | Care team members and roles |
| `patientSummary` | `PatientSummary` | Demographics and identifiers |
| `resourceCounts` | `ResourceCounts` | Record counts by category |
| `healthDashboard` | `HealthDashboard` | Combined counts + patient summary |

### Smart Health Links REST API

**Secured endpoints** — `POST` with `X-Consumer-Id` header:

| Endpoint | Description |
|---|---|
| `/secure/api/v1/shl/create` | Create a new Smart Health Link |
| `/secure/api/v1/shl/search` | Search for existing links |
| `/secure/api/v1/shl/get` | Get a single link's details |
| `/secure/api/v1/shl/preview` | Preview link data as JSON |
| `/secure/api/v1/shl/revoke` | Revoke an active link |

**Public endpoints** — no authentication:

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/shl/{id}?recipient=...` | Retrieve encrypted JWE payload |
| `POST` | `/shl/{id}` | Retrieve SHL manifest |

## Project Structure

```
src/main/java/com/chanakya/hsapi/
├── audit/        # Centralized audit logging
├── auth/         # Authentication and authorization
├── common/       # Shared utilities
├── config/       # Spring and AWS configuration
├── crosswalk/    # Patient identity mapping
├── crypto/       # JWE encryption/decryption
├── fhir/         # FHIR bundle building and HealthLake client
├── graphql/      # GraphQL query resolvers
├── pdf/          # PDF generation (OpenHTMLToPDF)
├── shl/          # Smart Health Links (controllers, services, models)
└── storage/      # S3 payload storage
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/hsapi` | MongoDB connection string |
| `MONGODB_DATABASE` | `hsapi` | MongoDB database name |
| `AWS_REGION` | `us-east-1` | AWS region for all services |
| `HEALTHLAKE_ENDPOINT` | — | AWS HealthLake FHIR endpoint URL |
| `S3_BUCKET` | — | S3 bucket for encrypted payloads |
| `ENCRYPTION_KEY` | — | AES key for JWE encryption |
| `APP_BASE_URL` | `http://localhost:8080` | Public base URL for SHL links |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Allowed CORS origins |
| `OAUTH2_ISSUER_URI` | — | OAuth2 token issuer URI |

## Documentation

| Document | Description |
|---|---|
| [Product Overview](docs/product-overview.md) | Features, user stories, and system capabilities |
| [Architecture](docs/architecture.md) | System design, data flows, and technical decisions |
| [Enterprise Architecture](docs/ea-architecture.md) | Integration context and deployment topology |
| [Developer Guide](docs/dev-guide.md) | Local setup, build, test, and contribution workflow |
| [API Consumer Guide](docs/api-consumer-guide.md) | Authentication, queries, and endpoint reference |
| [Data Dictionary](docs/data-dictionary.md) | GraphQL types, fields, and FHIR mappings |
| [QE Guide](docs/qe-guide.md) | Test strategy, test cases, and quality processes |
| [Operations Guide](docs/ops-guide.md) | Deployment, monitoring, and incident response |
| [E2E cURL Commands](docs/e2e-curl-commands.md) | Ready-to-run cURL examples for all endpoints |
