# SHL API End-to-End Verification — curl Commands

All commands verified against `http://localhost:8080` on March 6, 2026.
Requires: Spring Boot app running with `dev` profile, MongoDB, AWS HealthLake.

---

## 0. Prerequisites

```bash
# Start MongoDB (if not running)
docker compose up -d mongo

# Start the app
./mvnw spring-boot:run
```

---

## 1. Health Check

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

**Expected:** `{"status":"UP"}` with mongo, ssl, liveness, readiness components.

---

## 2. Auth Boundary — 401 Without Header

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/secure/api/v1/shl/search \
  -H "Content-Type: application/json" \
  -d '{"idType":"EID","idValue":"ENT001"}'
```

**Expected:** `401`

---

## 3. Search Links (Secured)

```bash
curl -s -X POST http://localhost:8080/secure/api/v1/shl/search \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d '{"idType":"EID","idValue":"ENT001"}' | python3 -m json.tool
```

**Expected:** Array of link objects (empty if no links exist yet).

---

## 4. Create Snapshot Link (with PDF)

```bash
curl -s -X POST http://localhost:8080/secure/api/v1/shl/create \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d '{
    "idType": "EID",
    "idValue": "ENT001",
    "label": "Patient Health Summary for Dr. Smith",
    "expiresAt": "2026-03-07T00:00:00Z",
    "selectedResources": ["MedicationRequest","Condition","AllergyIntolerance","Immunization","Observation"],
    "includePdf": true,
    "patientName": "Test Patient",
    "mode": "snapshot"
  }' | python3 -m json.tool
```

**Expected:**
```json
{
    "linkId": "<43-char-base64url>",
    "shlinkUrl": "shlink:/<base64url-payload>",
    "expiresAt": "2026-03-07T00:00:00Z"
}
```

Save the `linkId` for subsequent commands:
```bash
SNAPSHOT_ID="<paste-linkId-here>"
```

---

## 5. Get Link Detail (Secured)

```bash
curl -s -X POST http://localhost:8080/secure/api/v1/shl/get \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d "{\"linkId\":\"$SNAPSHOT_ID\"}" | python3 -m json.tool
```

**Expected:** Full link detail with `effectiveStatus: "active"`, `flag: "U"`, `mode: "snapshot"`, `accessHistory: []`.

---

## 6. Preview FHIR Bundle (Secured)

```bash
curl -s -X POST http://localhost:8080/secure/api/v1/shl/preview \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d "{\"linkId\":\"$SNAPSHOT_ID\"}" | python3 -m json.tool
```

**Expected:** FHIR Bundle (type=collection) with Patient, DocumentReference, and selected resource types.

---

## 7. Public GET — Missing Recipient (400)

```bash
curl -s -o /dev/null -w "%{http_code}" \
  "http://localhost:8080/shl/$SNAPSHOT_ID"
```

**Expected:** `400` (recipient query param is required)

---

## 8. Public GET — Retrieve Snapshot JWE

```bash
curl -s "http://localhost:8080/shl/$SNAPSHOT_ID?recipient=Dr.%20Smith"
```

**Expected:** JWE compact serialization string (5 dot-separated segments), `Content-Type: application/jose`.

---

## 9. Decode SHLink Payload

```bash
# Extract from shlinkUrl returned in step 4
SHLINK_URL="<paste-shlinkUrl-here>"
PAYLOAD=$(echo "$SHLINK_URL" | sed 's|shlink:/||')

# Decode base64url
python3 -c "
import base64, json
payload = '$PAYLOAD'
# Add padding
payload += '=' * (4 - len(payload) % 4)
decoded = base64.urlsafe_b64decode(payload)
print(json.dumps(json.loads(decoded), indent=2))
"
```

**Expected:**
```json
{
  "url": "http://localhost:8080/shl/<linkId>",
  "flag": "U",
  "key": "<43-char-base64url-AES-key>",
  "exp": 1772956800,
  "label": "Patient Health Summary for Dr. Smith"
}
```

---

## 10. Verify JWE Header Compliance

```bash
JWE=$(curl -s "http://localhost:8080/shl/$SNAPSHOT_ID?recipient=Dr.%20Smith")
HEADER=$(echo "$JWE" | cut -d. -f1)

python3 -c "
import base64, json
header = '$HEADER'
header += '=' * (4 - len(header) % 4)
decoded = json.loads(base64.urlsafe_b64decode(header))
print(json.dumps(decoded, indent=2))
assert decoded['alg'] == 'dir', 'alg must be dir'
assert decoded['enc'] == 'A256GCM', 'enc must be A256GCM'
assert decoded['cty'] == 'application/fhir+json', 'cty must be application/fhir+json'
assert decoded['zip'] == 'DEF', 'zip must be DEF'
print('All JWE header checks passed')
"
```

**Expected:**
```json
{
  "zip": "DEF",
  "cty": "application/fhir+json",
  "enc": "A256GCM",
  "alg": "dir"
}
```

---

## 11. Decrypt JWE and Validate FHIR Bundle

```bash
# Requires: pip3 install jwcrypto

JWE=$(curl -s "http://localhost:8080/shl/$SNAPSHOT_ID?recipient=Dr.%20Smith")
KEY="<paste-key-from-shlink-payload>"

python3 -c "
from jwcrypto import jwe, jwk
import base64, json

key_b64url = '$KEY'
# Decode base64url key and create JWK
key_bytes = base64.urlsafe_b64decode(key_b64url + '==')
symmetric_key = jwk.JWK(kty='oct', k=base64.urlsafe_b64encode(key_bytes).decode().rstrip('='))

# Decrypt
jwe_token = jwe.JWE()
jwe_token.deserialize('$JWE')
jwe_token.decrypt(symmetric_key)
payload = jwe_token.payload.decode('utf-8')

bundle = json.loads(payload)
print('resourceType:', bundle['resourceType'])
print('type:', bundle['type'])
print('entries:', len(bundle.get('entry', [])))

# List resource types
types = [e['resource']['resourceType'] for e in bundle.get('entry', [])]
from collections import Counter
for rt, count in Counter(types).items():
    print(f'  {rt}: {count}')
"
```

**Expected:** FHIR Bundle with type=collection, Patient + selected resource types.

---

## 12. Extract PDF from Decrypted Bundle

```bash
python3 -c "
from jwcrypto import jwe, jwk
import base64, json

key_b64url = '$KEY'
key_bytes = base64.urlsafe_b64decode(key_b64url + '==')
symmetric_key = jwk.JWK(kty='oct', k=base64.urlsafe_b64encode(key_bytes).decode().rstrip('='))

jwe_token = jwe.JWE()
jwe_token.deserialize(open('/dev/stdin').read() if False else '$(curl -s "http://localhost:8080/shl/$SNAPSHOT_ID?recipient=Dr.%20Smith")')
jwe_token.decrypt(symmetric_key)
bundle = json.loads(jwe_token.payload.decode('utf-8'))

# Find DocumentReference with PDF
for entry in bundle.get('entry', []):
    res = entry['resource']
    if res['resourceType'] == 'DocumentReference':
        for content in res.get('content', []):
            attachment = content.get('attachment', {})
            if attachment.get('contentType') == 'application/pdf':
                pdf_data = base64.b64decode(attachment['data'])
                with open('patient-summary.pdf', 'wb') as f:
                    f.write(pdf_data)
                print(f'PDF saved: {len(pdf_data)} bytes')
                print(f'Valid PDF: {pdf_data[:4] == b\"%PDF\"}')"
```

**Expected:** PDF file saved with valid `%PDF` magic bytes.

---

## 13. Create Live Link

```bash
curl -s -X POST http://localhost:8080/secure/api/v1/shl/create \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d '{
    "idType": "EID",
    "idValue": "ENT001",
    "label": "Live Health Data",
    "expiresAt": "2026-03-07T00:00:00Z",
    "selectedResources": ["Condition","MedicationRequest"],
    "includePdf": false,
    "mode": "live"
  }' | python3 -m json.tool
```

**Expected:** `linkId`, `shlinkUrl` with `flag: "L"`.

Save the `linkId`:
```bash
LIVE_ID="<paste-linkId-here>"
```

---

## 14. Retrieve Live Manifest (POST)

```bash
curl -s -X POST "http://localhost:8080/shl/$LIVE_ID" \
  -H "Content-Type: application/json" \
  -d '{"recipient":"Dr. Smith"}' | python3 -m json.tool
```

**Expected:**
```json
{
    "status": "can-change",
    "files": [
        {
            "contentType": "application/fhir+json",
            "embedded": "<JWE-compact-serialization>"
        }
    ]
}
```

---

## 15. Verify Access History

```bash
curl -s -X POST http://localhost:8080/secure/api/v1/shl/get \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d "{\"linkId\":\"$LIVE_ID\"}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print('Status:', data['effectiveStatus'])
print('Access History:')
for h in data.get('accessHistory', []):
    print(f'  {h[\"action\"]} by {h[\"recipient\"]} at {h[\"timestamp\"]}')
"
```

**Expected:** Access history entries with recipient, action (ACCESSED), and timestamp.

---

## 16. Revoke a Link

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/secure/api/v1/shl/revoke \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d "{\"linkId\":\"$SNAPSHOT_ID\"}"
```

**Expected:** `200` (empty body)

---

## 17. Verify Revoked Link Returns 404 (Public GET)

```bash
curl -s -o /dev/null -w "%{http_code}" \
  "http://localhost:8080/shl/$SNAPSHOT_ID?recipient=Dr.%20Smith"
```

**Expected:** `404`

---

## 18. Verify Revoked Link Status

```bash
curl -s -X POST http://localhost:8080/secure/api/v1/shl/get \
  -H "X-Consumer-Id: test-consumer" \
  -H "Content-Type: application/json" \
  -d "{\"linkId\":\"$SNAPSHOT_ID\"}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print('Status:', data['effectiveStatus'])
print('Last access:', data['accessHistory'][-1] if data.get('accessHistory') else 'none')
"
```

**Expected:** `effectiveStatus: "revoked"`, last access record shows `ACCESS_REVOKED`.

---

## 19. Cross-Method Guards

### GET on Live Link (should be 404)
```bash
curl -s -o /dev/null -w "%{http_code}" \
  "http://localhost:8080/shl/$LIVE_ID?recipient=Dr.%20Smith"
```
**Expected:** `404` (L-flag links use POST, not GET)

### POST on Snapshot Link (should be 404)
```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "http://localhost:8080/shl/$SNAPSHOT_ID" \
  -H "Content-Type: application/json" \
  -d '{"recipient":"Dr. Smith"}'
```
**Expected:** `404` (U-flag links use GET, not POST)

---

## 20. Security Headers

```bash
curl -sv http://localhost:8080/actuator/health 2>&1 | grep -E "^< (X-|Strict|Content-Security|Cache|Referrer)"
```

**Expected:**
```
< X-Request-Id: <uuid>
< X-Content-Type-Options: nosniff
< X-Frame-Options: DENY
< X-XSS-Protection: 0
< Referrer-Policy: strict-origin-when-cross-origin
< Content-Security-Policy: default-src 'none'; frame-ancestors 'none'
< Strict-Transport-Security: max-age=31536000; includeSubDomains
< Cache-Control: no-store
```

---

## 21. CORS — Public Endpoint (All Origins)

```bash
curl -sv -X OPTIONS "http://localhost:8080/shl/test" \
  -H "Origin: https://any-domain.example.com" \
  -H "Access-Control-Request-Method: GET" 2>&1 | grep "Access-Control"
```

**Expected:** `Access-Control-Allow-Origin: *`

---

## 22. CORS — Secured Endpoint (Restricted Origins)

```bash
curl -sv -X OPTIONS http://localhost:8080/secure/api/v1/shl/search \
  -H "Origin: https://malicious.example.com" \
  -H "Access-Control-Request-Method: POST" 2>&1 | grep "Access-Control-Allow-Origin"
```

**Expected:** No `Access-Control-Allow-Origin` header (blocked).

---

## 23. Audit Log (MongoDB)

```bash
docker exec <mongo-container> mongosh --quiet --eval '
  JSON.stringify(
    db.getSiblingDB("healthsafe").shl_audit_log
      .find({},{_id:0}).sort({timestamp:-1}).limit(5).toArray(),
    null, 2
  )'
```

**Expected:** Audit entries with `linkId`, `action`, `recipient`, `ipAddress`, `userAgent`, `requestId`, `timestamp`, and `contentHash` (for LINK_ACCESSED).

---

## Verification Summary

| # | Test | Method | Expected | Verified |
|---|------|--------|----------|----------|
| 1 | Health check | GET /actuator/health | 200, status=UP | ✅ |
| 2 | Auth boundary | POST /secure/... (no header) | 401 | ✅ |
| 3 | Search links | POST /secure/.../search | 200, array | ✅ |
| 4 | Create snapshot | POST /secure/.../create | 200, linkId+shlinkUrl | ✅ |
| 5 | Get link detail | POST /secure/.../get | 200, full detail | ✅ |
| 6 | Preview bundle | POST /secure/.../preview | 200, FHIR Bundle | ✅ |
| 7 | Missing recipient | GET /shl/{id} (no param) | 400 | ✅ |
| 8 | Retrieve snapshot JWE | GET /shl/{id}?recipient=... | 200, JWE (5 segments) | ✅ |
| 9 | SHLink payload | Decode shlink:/ | url, flag, key, exp, label | ✅ |
| 10 | JWE header | Decode JWE header | dir, A256GCM, fhir+json, DEF | ✅ |
| 11 | Decrypt JWE | jwcrypto decrypt | Valid FHIR Bundle | ✅ |
| 12 | Extract PDF | Base64 decode from Bundle | Valid %PDF | ✅ |
| 13 | Create live link | POST /secure/.../create | 200, flag=L | ✅ |
| 14 | Live manifest | POST /shl/{id} | can-change, 1 file, embedded JWE | ✅ |
| 15 | Access history | POST /secure/.../get | ACCESSED records | ✅ |
| 16 | Revoke link | POST /secure/.../revoke | 200 | ✅ |
| 17 | Revoked GET | GET /shl/{id}?recipient=... | 404 | ✅ |
| 18 | Revoked status | POST /secure/.../get | effectiveStatus=revoked | ✅ |
| 19 | Cross-method guard | GET on L-flag / POST on U-flag | 404 both | ✅ |
| 20 | Security headers | Any response | All OWASP headers present | ✅ |
| 21 | CORS public | OPTIONS /shl/... | Allow-Origin: * | ✅ |
| 22 | CORS secured | OPTIONS /secure/... | Origin blocked | ✅ |
| 23 | Audit log | MongoDB query | Full audit trail | ✅ |
