# Definitive CLAUDE.md skill inventory for GraphQL + FHIR on AWS HealthLake

**This stack combines Java 25 LTS, Spring Boot 4.0.3, Spring for GraphQL 2.0, HAPI FHIR 8.6, and AWS HealthLake — a potent but version-sensitive combination with several critical compatibility gaps.** The biggest landmine is the HAPI FHIR ↔ Jackson 3 conflict: Spring Boot 4 defaults to Jackson 3 (`tools.jackson`), but HAPI FHIR 8.x is deeply coupled to Jackson 2 (`com.fasterxml.jackson`). Developers must run both Jackson versions simultaneously and isolate serialization paths. Below is the verified skill inventory with exact version numbers, key mistakes to avoid, and the single most important CLAUDE.md rule per area.

---

## 1. Java 25 (JDK 25 LTS — GA September 16, 2025)

**JDK 25 is the first LTS since JDK 21**, currently at patch **25.0.2**. It finalizes 7 features from preview/incubator and adds 5 new permanent JEPs (18 total). The gap from Java 21 → 25 spans four intermediate releases (22–25), each with significant additions.

### Confirmed finalized features relevant to this stack

| Feature | JEP | Finalized In | Notes |
|---|---|---|---|
| Virtual Threads | 444 | **JDK 21** | Production-ready since 21; JDK 24 added JEP 491 (reduced pinning in `synchronized`) |
| Record Patterns | 440 | **JDK 21** | Destructuring in `instanceof` and `switch` |
| Scoped Values | 506 | **JDK 25** | Replaces `ThreadLocal` for virtual threads. `orElse()` no longer accepts null |
| Key Derivation Function API | 510 | **JDK 25** | `javax.crypto.KDF` — for HKDF, Argon2, etc. |
| Module Import Declarations | 511 | **JDK 25** | `import module java.base;` |
| Compact Source Files / Instance Main | 512 | **JDK 25** | Simplified `main()` for scripts/prototypes |
| Flexible Constructor Bodies | 513 | **JDK 25** | Code before `super()`/`this()` calls |
| Compact Object Headers | 519 | **JDK 25** | 96–128 bits → **64 bits** (Project Lilliput) |
| Generational Shenandoah | 521 | **JDK 25** | Low-latency GC with generational mode |

### Features still NOT finalized (or removed)

| Feature | JEP | Status in JDK 25 |
|---|---|---|
| Structured Concurrency | 505 | **5th Preview** — Joiner-based API still evolving; JEP 525 (6th preview) proposed for JDK 26 |
| Primitive Types in Patterns | 507 (was 488) | **3rd Preview** — no changes from 2nd preview |
| Value Classes | 401 | **Not in JDK 25 at all** — Valhalla EA build published Oct 2025; earliest preview JDK 26–27 |
| String Templates | — | **Withdrawn and removed in JDK 23** — Brian Goetz declared design "unsuitable"; no replacement proposed |
| Vector API | 508 | **10th Incubator** — blocked on Project Valhalla |

### Top CLAUDE.md rule
> **RULE: Structured Concurrency (JEP 505) and Primitive Types in Patterns (JEP 507) require `--enable-preview` in Java 25. String Templates do not exist — they were withdrawn in JDK 23. Never generate `STR."..."` or `RAW."..."` syntax. Scoped Values (JEP 506) ARE final — use `ScopedValue.where(KEY, val).run(...)` without preview flags. Value Classes (JEP 401) do not exist yet.**

---

## 2. Spring Boot 4.0 (GA November 20, 2025 — latest 4.0.3)

### Top CLAUDE.md rule
> **RULE: Spring Boot 4 defaults to Jackson 3 (`tools.jackson` groupId, `JsonMapper` class). HAPI FHIR 8.x requires Jackson 2 (`com.fasterxml.jackson`, `ObjectMapper`). You MUST configure dual Jackson support — use Jackson 3 for Spring MVC/GraphQL serialization and Jackson 2 for HAPI FHIR. Never mix the two on the same serialization path. Set `spring.jackson2.*` properties for HAPI FHIR contexts. The virtual threads property is `spring.threads.virtual.enabled=true`.**

---

## 3. Spring for GraphQL 2.0 (GA November 18, 2025)

### Top CLAUDE.md rule
> **RULE: Always use `@BatchMapping` (not `@SchemaMapping`) for association fields to prevent N+1 queries. Return `Mono<Map<Parent, Child>>` or `Flux<Child>` keyed to the parent list. For pagination, define only the node type in the schema — `ConnectionTypeDefinitionConfigurer` auto-generates Connection/Edge/PageInfo types. Use `@GraphQlTest` (not `@WebMvcTest`) for GraphQL controller slice tests.**

---

## 4. HAPI FHIR 8.6.2 (latest stable, February 2026)

### Top CLAUDE.md rule
> **RULE: HAPI FHIR 8.x uses Jackson 2 (`com.fasterxml.jackson`) exclusively. It does NOT support Jackson 3. When running under Spring Boot 4 (which defaults to Jackson 3), you must explicitly include `com.fasterxml.jackson.core:jackson-databind:2.18+` and configure a separate `ObjectMapper` for HAPI FHIR. Never register HAPI FHIR's `IGenericClient` or `FhirContext` with Spring's Jackson 3 `JsonMapper`. Use `@JsonComponent` carefully — it targets Jackson 3 by default in Boot 4.**

---

## 5. GraphQL Java 25.0 + java-dataloader 5.0.3

### Top CLAUDE.md rule
> **RULE: Register `MaxQueryComplexityInstrumentation` and `MaxQueryDepthInstrumentation` as `@Bean` definitions — Spring for GraphQL 2.0 auto-detects all `Instrumentation` beans. DataLoader is v5 (NOT v3) — `CacheMap` implementations must be thread-safe. Never use graphql-java v23 (declared "poisoned"). The current stable is v25.0.**

---

## 6. AWS SDK for Java v2 (BOM 2.42.4) + HealthLake

### Top CLAUDE.md rule
> **RULE: The default Apache 4.5 sync HTTP client pins virtual threads. Always configure `Apache5HttpClient` (`software.amazon.awssdk:apache5-client`, GA since v2.41.0) or `AwsCrtHttpClient` when using `spring.threads.virtual.enabled=true`. For EKS, use Pod Identity (not IRSA) for new clusters. HealthLake supports FHIR R4 only — never attempt R5 resources against it. AWS SDK v1 is EOL since Dec 31, 2025.**

---

## 7. Testcontainers 2.0.3 (December 2025)

### Top CLAUDE.md rule
> **RULE: Testcontainers 2.0 changed all artifact names and packages. Use `org.testcontainers:testcontainers-mongodb:2.0.3` (not `org.testcontainers:mongodb`). Import `org.testcontainers.mongodb.MongoDBContainer` (not `org.testcontainers.containers.MongoDBContainer`). JUnit 4 `@Rule` is removed — use `@Testcontainers` + `@Container` exclusively. Use `@ServiceConnection` for auto-configured connection properties.**

---

## 8. Bucket4j 8.16.1 (February 2026)

### Top CLAUDE.md rule
> **RULE: The `bucket4j-spring-boot-starter` does NOT support Spring Boot 4 yet. Use `com.bucket4j:bucket4j_jdk17-core:8.16.1` directly with programmatic `Bucket.builder()` API. For distributed rate limiting with MongoDB, use `com.bucket4j:bucket4j_jdk17-mongodb-sync:8.16.1`. The artifact groupId is `com.bucket4j` (not `com.github.vladimir-bukhtoyarov`).**

---

## 9. Micrometer 1.16.x (NOT 2.0) + OTLP

### Top CLAUDE.md rule
> **RULE: Micrometer is at 1.16.x in Spring Boot 4 (NOT 2.0). Use `spring-boot-starter-opentelemetry` for OTLP export. The `@Observed` annotation requires `org.aspectj:aspectjweaver` on the classpath and `management.observations.annotations.enabled=true`. OTLP metrics may auto-enable on upgrade — check `management.otlp.metrics.export.*` properties.**

---

## 10. MongoDB driver 5.6.3 + Spring Data MongoDB 5.0.x

### Top CLAUDE.md rule
> **RULE: Use imperative `MongoRepository` (not `ReactiveMongoRepository`) with `spring.threads.virtual.enabled=true` — virtual threads give equivalent I/O scalability without reactive complexity. For GraphQL pagination, return `Window<T>` from repository methods and let Spring for GraphQL's `ConnectionAdapter` convert to Connection spec. MongoDB driver 5.6.x is virtual-thread-safe.**

---

## The five most critical cross-cutting CLAUDE.md rules

1. **Jackson dual-stack isolation**: Spring Boot 4 defaults to Jackson 3 (`tools.jackson`, `JsonMapper`). HAPI FHIR 8.x requires Jackson 2 (`com.fasterxml.jackson`, `ObjectMapper`). Both JARs must coexist. Never let HAPI FHIR serialize through Spring's default `JsonMapper`, and never configure `@JsonComponent` beans expecting them to apply to FHIR resources.

2. **Virtual thread HTTP client selection**: Setting `spring.threads.virtual.enabled=true` means ALL blocking calls run on virtual threads. The default AWS SDK sync HTTP client (Apache 4.5) pins carrier threads. Always configure `Apache5HttpClient` or `AwsCrtHttpClient` explicitly. MongoDB driver 5.6.x is virtual-thread-safe by default.

3. **Preview feature gating**: Structured Concurrency (`StructuredTaskScope`) and Primitive Types in Patterns require `--enable-preview` in Java 25. Scoped Values are final and do NOT need it. String Templates do not exist. Never generate code using preview features without the corresponding compiler/runtime flags.

4. **Testcontainers 2.0 artifact migration**: All artifact names changed (`mongodb` → `testcontainers-mongodb`), all packages relocated (`org.testcontainers.containers.*` → `org.testcontainers.<module>.*`). Spring Boot 4's `@ServiceConnection` expects the new 2.0 classes.

5. **HealthLake is R4-only**: AWS HealthLake exclusively supports FHIR R4 v4.0.1. Never generate R5 resources or use R5-only search parameters against HealthLake. HAPI FHIR's `FhirContext` must be initialized with `FhirContext.forR4()` for all HealthLake interactions.

## Consolidated version reference table

| Technology | Version | Key Artifact |
|---|---|---|
| JDK | **25.0.2** (LTS) | `openjdk-25` |
| Spring Boot | **4.0.3** | `spring-boot-starter` |
| Spring Framework | **7.0.5** | `spring-framework-bom` |
| Spring for GraphQL | **2.0.x** | `spring-boot-starter-graphql` |
| Spring Security | **7.0.3** | `spring-boot-starter-security` |
| Spring Data MongoDB | **5.0.x** | `spring-boot-starter-data-mongodb` |
| Jackson | **3.0.x** | `tools.jackson:jackson-bom` |
| GraphQL Java | **25.0** | `com.graphql-java:graphql-java` |
| java-dataloader | **5.0.3** | `com.graphql-java:java-dataloader` |
| HAPI FHIR | **8.6.2** | `ca.uhn.hapi.fhir:hapi-fhir-base` |
| AWS SDK Java v2 | **2.42.4** (BOM) | `software.amazon.awssdk:bom` |
| Micrometer | **1.16.x** | `io.micrometer:micrometer-bom` |
| Testcontainers | **2.0.3** | `org.testcontainers:testcontainers-bom` |
| Bucket4j | **8.16.1** | `com.bucket4j:bucket4j_jdk17-core` |
| MongoDB Java Driver | **5.6.3** | `org.mongodb:mongodb-driver-sync` |
