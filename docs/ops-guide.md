# Deployment and Operations Guide

This guide covers building, deploying, configuring, monitoring, and troubleshooting
the hs-api service. The application is a Spring Boot 4.0.3 / Java 25 backend deployed
on Amazon EKS, backed by MongoDB, AWS HealthLake (FHIR R4), and S3.

---

## 1. Build and Package

### Build the JAR (skip tests)

```bash
./mvnw clean package -DskipTests
```

### Run the test suite

```bash
./mvnw test
```

### JAR location

```
target/hs-api-0.0.1-SNAPSHOT.jar
```

### Run the JAR locally

```bash
java -jar target/hs-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

### Docker build

Example `Dockerfile`:

```dockerfile
FROM eclipse-temurin:25-jre-alpine
COPY target/hs-api-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build and tag:

```bash
docker build -t hs-api:latest .
```

### Virtual threads

Virtual threads are enabled by default (`spring.threads.virtual.enabled=true`).
No thread pool tuning is needed. The application handles high concurrency with
modest resource allocation because virtual threads are unbounded and scheduled by
the JVM rather than a fixed pool.

---

## 2. Environment Configuration

### Full variable reference

| Variable | Required | Default | Description |
|---|---|---|---|
| `MONGODB_URI` | Yes (prod) | `mongodb://localhost:27017/hsapi` | MongoDB connection string |
| `MONGODB_DATABASE` | No | `hsapi` | MongoDB database name |
| `AWS_REGION` | No | `ap-south-1` | AWS region for all services |
| `HEALTHLAKE_ENDPOINT` | Yes | -- | HealthLake datastore FHIR R4 endpoint URL |
| `S3_BUCKET` | Yes | -- | S3 bucket name for JWE payload storage |
| `ENCRYPTION_KEY` | Yes | -- | 32-byte string for AES-GCM field encryption |
| `APP_BASE_URL` | Yes (prod) | `http://localhost:8080` | Public base URL used in SHLink URIs |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:3000` | Comma-separated allowed CORS origins |
| `OAUTH2_ISSUER_URI` | No | -- | OAuth2 JWT issuer URI (optional JWT validation) |
| `SPRING_PROFILES_ACTIVE` | No | -- | Active profiles (e.g., `dev`, `prod`) |

### Secrets management

- **`ENCRYPTION_KEY`**: Must be exactly 32 bytes. Store in AWS Secrets Manager or a
  Kubernetes Secret. Never commit to version control.
- **`MONGODB_URI`**: Contains credentials. Store in a Kubernetes Secret and mount as
  an environment variable.
- **AWS credentials**: Use EKS Pod Identity (preferred for new clusters) or IRSA
  (legacy). Do not bake credentials into container images or ConfigMaps.

---

## 3. EKS Deployment

### Pod Identity

Pod Identity is the preferred authentication mechanism for AWS services (HealthLake,
S3, Secrets Manager). Configure a Kubernetes service account with an associated IAM
role that grants the required permissions.

Required IAM permissions:

- `healthlake:ReadResource`, `healthlake:SearchWithGet`, `healthlake:SearchWithPost`
  on the HealthLake datastore ARN
- `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` on the S3 bucket ARN
- `secretsmanager:GetSecretValue` if retrieving secrets from Secrets Manager

### Health probes

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 5
```

The `/health` endpoint returns `{"status":"UP"}` when the application is healthy.
In production, detailed health information is suppressed for security.

### Resource recommendations

```yaml
resources:
  requests:
    cpu: 500m
    memory: 512Mi
  limits:
    cpu: 2000m
    memory: 1Gi
```

- Virtual threads mean the app handles high concurrency with modest CPU.
- No thread pool sizing is needed (virtual threads are unbounded).
- Memory headroom is important for FHIR bundle processing and PDF generation.

### Scaling

The application is stateless and supports horizontal scaling via HPA.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: hs-api-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: hs-api
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

Key scaling notes:

- Minimum replicas: 2 (high availability).
- Scale on CPU utilization with a target of 70%.
- No sticky sessions required.
- MongoDB connections scale linearly with pod count; size the connection pool and
  MongoDB cluster accordingly.

### Rolling updates

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

This ensures zero-downtime deployments by keeping all existing pods running until
new pods pass readiness checks.

---

## 4. Monitoring

### Health endpoint

| Endpoint | Method | Response |
|---|---|---|
| `/health` | GET | `{"status":"UP"}` (no details in prod) |

### Structured logs

Logs are emitted in JSON format for aggregation by Splunk, Datadog, CloudWatch Logs,
or any JSON-aware log collector.

Key log fields to monitor:

| Field | Description |
|---|---|
| `requestId` | `X-Request-Id` correlation ID |
| `consumerId` | Authenticated consumer identity |
| `action` | SHL action performed |
| `enterpriseId` | Patient scope identifier |
| `duration` | Request processing time in milliseconds |

### Alerts to configure

Configure alerts for the following conditions:

| Alert | Condition | Severity |
|---|---|---|
| Health check failure | `/health` returns non-200 | Critical |
| High error rate | Error rate > 1% on `/secure/api/**` endpoints | High |
| HealthLake failures | HealthLake connection timeouts or 5xx responses | High |
| MongoDB pool exhaustion | MongoDB connection pool saturation | Critical |
| S3 failures | S3 upload or download errors | High |
| High latency | p99 response time > 5 seconds | Medium |
| Pod restarts | Restart count > 2 in 10 minutes | High |

---

## 5. MongoDB Operations

### Collections

| Collection | Purpose |
|---|---|
| `shl_links` | SHL link metadata (see [data-dictionary.md](data-dictionary.md)) |
| `shl_audit_log` | SHL action audit trail |
| `audit_log` | General FHIR query audit log |
| `patient_crosswalk` | Enterprise-to-HealthLake patient ID mapping |

### Indexes

Indexes are created programmatically on startup via `MongoConfig`. Automatic index
creation is disabled (`auto-index-creation: false`); indexes are managed in code,
not by Spring Data.

| Collection | Index Name | Fields | Notes |
|---|---|---|---|
| `shl_links` | `idx_enterpriseId_status` | `enterpriseId`, `status` | Compound |
| `shl_links` | `idx_expiresAt` | `expiresAt` | For expiration queries |
| `shl_audit_log` | `idx_linkId_timestamp` | `linkId`, `timestamp` | Compound |
| `shl_audit_log` | `idx_enterpriseId_timestamp` | `enterpriseId`, `timestamp` | Compound |
| `patient_crosswalk` | `idx_enterpriseId_unique` | `enterpriseId` | Unique |
| `audit_log` | `idx_enterpriseId_timestamp` | `enterpriseId`, `timestamp` | Compound |

### Backup

- **MongoDB Atlas**: Use automated continuous backups with point-in-time recovery.
- **Self-hosted**: Schedule `mongodump` to S3 or another durable store.

### Audit log growth

The `shl_audit_log` and `audit_log` collections grow unbounded. Each API call
generates approximately one audit entry.

Mitigation options:

1. **TTL index** for automatic cleanup (see Section 8, Audit Retention).
2. **Periodic archival** of entries older than the retention period to S3 or cold
   storage.
3. **Monitoring**: Alert when collection size exceeds a threshold.

---

## 6. S3 Operations

### Bucket configuration

- Bucket name is configured via the `S3_BUCKET` environment variable.
- Enable server-side encryption: SSE-S3 (default) or SSE-KMS for compliance.
- Block public access on the bucket.

### Object key pattern

```
shl/
  {enterpriseId}/
    {linkId}.jwe
```

- Content type: `application/jose`
- Typical object size: 10 KB -- 500 KB (compressed JWE FHIR Bundle)

### Object lifecycle

| Event | S3 Action |
|---|---|
| SHL snapshot link created | Object uploaded to `shl/{enterpriseId}/{linkId}.jwe` |
| SHL link revoked | Object deleted |
| SHL link expires | Object may become orphaned |

Consider an S3 lifecycle policy to clean up orphaned objects. For example, delete
objects with prefix `shl/` that are older than 400 days (beyond the maximum link
lifetime of 365 days).

### Required IAM permissions

```json
{
  "Effect": "Allow",
  "Action": [
    "s3:PutObject",
    "s3:GetObject",
    "s3:DeleteObject"
  ],
  "Resource": "arn:aws:s3:::BUCKET_NAME/shl/*"
}
```

---

## 7. Troubleshooting

### Common issues

| Symptom | Likely Cause | Resolution |
|---|---|---|
| 401 on `/secure/api/**` | Missing `X-Consumer-Id` header | Ensure the OAuth2 proxy or API gateway forwards the header |
| HealthLake timeouts | Network or IAM issue | Check Pod Identity role, VPC endpoints, security groups |
| MongoDB connection refused | MongoDB not running or wrong URI | Verify `MONGODB_URI`, check network connectivity |
| "Invalid encryption key" | `ENCRYPTION_KEY` wrong length | Must be exactly 32 bytes/characters |
| S3 `AccessDenied` | IAM permissions | Verify `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` on the bucket |
| JWE decryption fails | Key mismatch | Verify `ENCRYPTION_KEY` has not changed since link creation |
| Virtual thread pinning | Blocking inside `synchronized` block | Check for carrier thread pinning in JFR recordings; use `ReentrantLock` |
| GraphQL complexity error | Query exceeds limits | Max complexity: 200, max depth: 10; simplify the query |
| CORS errors | Origin not in allowed list | Update `CORS_ALLOWED_ORIGINS` environment variable |
| PDF generation OOM | Large FHIR bundles | Increase memory limit; check bundle size before rendering |

### AWS credential diagnostics

```bash
# Local development
aws sts get-caller-identity

# From within an EKS pod
kubectl exec -it <pod-name> -- aws sts get-caller-identity
```

If credentials are not found:

1. Verify the Pod Identity association exists for the service account.
2. Verify the IAM role trust policy allows the EKS cluster.
3. For IRSA (legacy): verify the service account annotation
   `eks.amazonaws.com/role-arn`.

### MongoDB diagnostics

```bash
# Check connection
mongosh "$MONGODB_URI" --eval "db.runCommand({ping: 1})"

# List collections
mongosh "$MONGODB_URI" --eval "db.getCollectionNames()"

# Check indexes on shl_links
mongosh "$MONGODB_URI" --eval "db.shl_links.getIndexes()"

# Count audit entries
mongosh "$MONGODB_URI" --eval "db.shl_audit_log.countDocuments()"

# Check collection sizes
mongosh "$MONGODB_URI" --eval "db.stats()"
```

### HealthLake diagnostics

```bash
# Test HealthLake connectivity (requires AWS credentials)
aws healthlake describe-fhir-datastore \
  --datastore-id <DATASTORE_ID> \
  --region ap-south-1

# Test a FHIR search
curl -H "Authorization: Bearer $(aws healthlake ...)" \
  "$HEALTHLAKE_ENDPOINT/Patient?_count=1"
```

### Log analysis

Search for errors in structured logs using common fields:

```bash
# Find all errors for a specific request
kubectl logs <pod> | jq 'select(.requestId == "REQ-123")'

# Find all HealthLake failures
kubectl logs <pod> | jq 'select(.message | contains("HealthLake"))'

# Find slow requests (> 3 seconds)
kubectl logs <pod> | jq 'select(.duration > 3000)'
```

---

## 8. Security Operations

### Encryption key rotation

There is currently no automated key rotation. The `ENCRYPTION_KEY` is used for
AES-GCM encryption of sensitive fields in MongoDB.

Rotation procedure:

1. Generate a new 32-byte encryption key.
2. Write a migration script to re-encrypt all `shl_links.encryptionKey` fields
   using the new key.
3. Deploy the migration during a maintenance window.
4. Update the `ENCRYPTION_KEY` environment variable across all pods.
5. Verify decryption works for existing links.

Mitigating factors:

- SHL links are short-lived (maximum 365 days). Most links expire before rotation
  would be needed.
- All active links become unreadable if the key changes without re-encryption.
  Always re-encrypt before rotating.

### Audit retention

Audit logs are stored in MongoDB indefinitely by default. Configure TTL indexes
for automatic cleanup:

```javascript
// Retain audit entries for 1 year (31,536,000 seconds)
db.shl_audit_log.createIndex(
  { timestamp: 1 },
  { expireAfterSeconds: 31536000 }
)

db.audit_log.createIndex(
  { timestamp: 1 },
  { expireAfterSeconds: 31536000 }
)
```

Adjust the retention period based on compliance requirements.

### Security headers

Security headers (Content-Security-Policy, X-Content-Type-Options,
X-Frame-Options, Strict-Transport-Security, etc.) are applied globally by
`SecurityHeadersFilter`. No configuration is needed. See
[ea-architecture.md](ea-architecture.md) for the full list.

### Network security

- The application listens on port 8080 (HTTP). TLS termination should be handled
  by an ingress controller or load balancer.
- Restrict inbound traffic to the application port only.
- Use VPC endpoints for AWS service access (HealthLake, S3, Secrets Manager) to
  keep traffic within the VPC.
- MongoDB should be accessible only from the application subnet.

---

## Cross-References

- **[data-dictionary.md](data-dictionary.md)** -- Collection schemas, field types,
  and validation rules.
- **[ea-architecture.md](ea-architecture.md)** -- Deployment topology, security
  architecture, and component diagrams.
- **[architecture.md](architecture.md)** -- Application architecture, design
  decisions, and module structure.
- **[api-consumer-guide.md](api-consumer-guide.md)** -- API usage guide for
  integrating consumers.
- **[e2e-curl-commands.md](e2e-curl-commands.md)** -- End-to-end curl examples for
  testing all endpoints.
