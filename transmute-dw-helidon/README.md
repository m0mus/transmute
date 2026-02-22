# transmute-dw-helidon

`MigrationSkill` implementations for migrating **Dropwizard 3** projects to
**Helidon 4 SE** (declarative HTTP server, service registry injection).

Depends only on `transmute-core`. No dependency on `transmute-agent` or any LLM
library — all four skills in this module are fully deterministic.

---

## Skills overview

Skills run in this order. Each skill declares its trigger conditions and
`@Skill(after=...)` constraints so the planner enforces the ordering automatically.

| Order | Skill | Scope | Strategy |
|-------|-------|-------|----------|
| 1 | `PomMigrationSkill` | PROJECT | Replace `pom.xml` with Helidon 4 SE template |
| 5 | `DwOrphansSkill` | PROJECT | Text-based DW_POJO / DW_COMMENT / DW_REMOVE |
| 10 | `JaxrsAnnotationsSkill` | PROJECT | OpenRewrite recipe chain |
| 20 | `HealthCheckSkill` | FILE | Regex-based HealthCheck API migration |

---

## PomMigrationSkill — order 1, PROJECT scope

Replaces the project `pom.xml` with a Helidon 4 SE parent POM template, preserving
the project's identity and non-Dropwizard dependencies.

### What it does

- Reads the original `pom.xml` from the output directory.
- Loads the Helidon 4 SE parent template from
  `classpath:/templates/helidon-declarative-pom.xml`.
- Merges:
  - `groupId`, `artifactId`, `version`, `name` from the original POM.
  - Non-Dropwizard, non-Helidon `<dependencies>` entries (third-party libraries are
    preserved).
  - Non-Dropwizard `<dependencyManagement>` entries (BOM imports excluded).
  - Custom `<properties>` not already present in the template.
- Re-adds transitive dependencies that DW bundles were providing implicitly:

  | Removed DW bundle | Re-added dependency |
  |-------------------|-------------------|
  | `dropwizard-hibernate` | `hibernate-core 6.4.4.Final`, `jakarta.persistence-api 3.1.0` |
  | `dropwizard-db` | `HikariCP 5.1.0` |
  | `dropwizard-migrations` | `liquibase-core 4.27.0` |
  | `dropwizard-views-freemarker` | `freemarker 2.3.32` |
  | `dropwizard-views-mustache` | `compiler 0.9.14` |
  | `dropwizard-jdbi3` | `jdbi3-core 3.45.0`, `jdbi3-sqlobject 3.45.0` |

### What it removes

Any dependency from these group IDs or patterns:
`io.dropwizard`, `org.glassfish.jersey`, `com.codahale.metrics`,
`javax.ws.rs`, `jakarta.ws.rs`, `org.eclipse.jetty`

### What it does NOT do

- Does not handle multi-module POM hierarchies (parent POMs or aggregators). Only
  the root `pom.xml` in the output directory is processed.
- Does not migrate custom Maven plugin configuration (e.g. Shade, Assembly, custom
  lifecycle bindings). Review plugin config manually after migration.

---

## DwOrphansSkill — order 5, PROJECT scope

Handles Dropwizard types that have no direct Helidon equivalent using three text-based
strategies. Works on raw source text — no AST required. Safe to run multiple times
(idempotent).

**Trigger:** any file containing imports starting with `io.dropwizard.` or
`com.codahale.metrics.`

### DW_POJO — strip extends/implements, keep the class body

Applied to types that were DW base classes or interfaces with no Helidon equivalent.
The extends/implements clause is removed; all fields, constructors, and methods are
preserved as plain Java. A `DW_MIGRATION_TODO[pojo]` comment is added at the class
level explaining what manual work remains.

| Removed base type | TODO hint |
|-------------------|-----------|
| `extends View` | Implement templating manually (Freemarker, Mustache via plain Java) |
| `extends AbstractDAO` | Inject `EntityManager` or `SessionFactory` directly |
| `implements Managed` | Register `start()`/`stop()` with Helidon lifecycle |
| `implements Authenticator` | Implement Helidon Security authenticator |
| `implements Authorizer` | Implement Helidon Security authorizer |
| `implements UnauthorizedHandler` | Implement Helidon Security handler |

### DW_COMMENT — comment out import and usages

Applied to types whose Java API has no Helidon equivalent. The import line is
commented out with a `DW_MIGRATION_TODO[removed]:` prefix; field declarations and
annotations of the type are commented out with a `DW_MIGRATION_TODO[manual]:` prefix.

Covered types:

| Type | Treatment |
|------|-----------|
| `io.dropwizard.auth.Auth` | `@Auth` replaced with inline comment |
| `io.dropwizard.hibernate.UnitOfWork` | `@UnitOfWork` replaced with line comment |
| `io.dropwizard.auth.AuthDynamicFeature` | Import commented out |
| `io.dropwizard.auth.AuthFilter` | Import commented out |
| `io.dropwizard.setup.Environment` | Field declaration commented out |
| `io.dropwizard.jersey.setup.JerseyEnvironment` | Field declaration commented out |
| `com.codahale.metrics.Counter/Timer/Meter/Histogram/Gauge/MetricRegistry` | Field declaration commented out |
| `javax/jakarta.annotation.security.PermitAll` | `@PermitAll` replaced with line comment |
| `javax/jakarta.annotation.security.RolesAllowed` | `@RolesAllowed(...)` replaced with line comment |
| `javax/jakarta.ws.rs.core.SecurityContext` | Type replaced with `Object` + inline comment |

### DW_REMOVE — silently remove import lines

Pure infrastructure plumbing that adds no value to the migrated code:

- `io.dropwizard.Bundle`
- `io.dropwizard.ConfiguredBundle`

---

## JaxrsAnnotationsSkill — order 10, PROJECT scope

Runs the full JAX-RS → Helidon annotation migration using OpenRewrite recipes plus
text-based post-processing. Operates on every Java file in the output directory.

**Trigger:** any file importing `javax.ws.rs.*` or `jakarta.ws.rs.*`
**Postcheck:** forbids residual `javax.ws.rs.*` or `jakarta.ws.rs.*` imports

### OpenRewrite recipe chain (in order)

**1. JaxrsToHelidonRecipe**

- Adds `@RestServer.Endpoint` (`io.helidon.webserver.http.RestServer`) to any class
  that has `@Path` on the class itself or any of its methods.
- Adds `@Service.Singleton` (`io.helidon.service.registry.Service`) to the same classes.
- Adds `@Http.Entity` to body parameters of `@POST`, `@PUT`, `@PATCH`, `@DELETE`
  methods that do not already have a param annotation.
- Removes `@Produces` and `@Consumes` via the `isProducesOrConsumes` guard (they are
  handled by the text post-processing step below).

**2. ChangeType recipes** — FQN renames for all JAX-RS annotations

| From (javax / jakarta) | To (Helidon) |
|------------------------|--------------|
| `*.ws.rs.GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS` | `io.helidon.http.Http$GET` etc. |
| `*.ws.rs.Path` | `io.helidon.http.Http$Path` |
| `*.ws.rs.PathParam` | `io.helidon.http.Http$PathParam` |
| `*.ws.rs.QueryParam` | `io.helidon.http.Http$QueryParam` |
| `*.ws.rs.HeaderParam` | `io.helidon.http.Http$HeaderParam` |
| `*.inject.Inject` | `io.helidon.service.registry.Service$Inject` |
| `*.inject.Singleton` | `io.helidon.service.registry.Service$Singleton` |

**3. DropwizardParamRecipe** — DW param wrapper types → Java types

| From | To | Import added |
|------|----|--------------|
| `LocalDateParam` | `LocalDate` | `java.time.LocalDate` |
| `IntParam` | `Integer` | — |
| `LongParam` | `Long` | — |
| `BooleanParam` | `Boolean` | — |
| `FloatParam` | `Float` | — |
| `DoubleParam` | `Double` | — |
| `StringParam` | `String` | — |
| `UUIDParam` | `UUID` | `java.util.UUID` |

Also removes `.get()` calls on method parameters that used these wrapper types.

**4. Response type migration**
- `javax.ws.rs.core.Response` → `io.helidon.webserver.http.ServerResponse`
- `jakarta.ws.rs.core.Response` → `io.helidon.webserver.http.ServerResponse`

**5. RemoveUnusedImports** — cleans up stale import statements.

### Text post-processing (after OpenRewrite)

| Transform | Result |
|-----------|--------|
| `@Produces(...)` / `@Consumes(...)` | Replaced with `DW_MIGRATION_TODO: Review @Produces/@Consumes` comment |
| `import javax/jakarta.ws.rs.Produces/Consumes` | Import line removed |
| `@Timed/@Metered/@ExceptionMetered/@Counted/@Gauge/@CacheControl` | Replaced with `DW_MIGRATION_TODO: Replace Dropwizard @<name>` comment |
| `import com.codahale.metrics.annotation.*` | Import line removed |
| `import io.dropwizard.jersey.caching.CacheControl` | Import line removed |
| `@Context` (field/param) | Replaced with `DW_MIGRATION_TODO: Replace Context injection` comment |
| `extends View` (Dropwizard views) | Extends clause removed; View return types replaced with `String`; `return new View(...)` replaced with `return ""` |
| `MediaType.` usage | `DW_MIGRATION_TODO: Review @Produces/@Consumes media types` added at top of file |
| `ServerResponse` usage | `DW_MIGRATION_TODO: Review ServerResponse usage` added at top of file |

### What it does NOT do

- Does not migrate `@FormParam` (no Helidon equivalent; left for manual review).
- Does not migrate JAX-RS `ExceptionMapper`, `ContainerRequestFilter`,
  `ContainerResponseFilter`, or other `@Provider` types — these require significant
  manual rework in Helidon's filter/error-handling model.
- Does not migrate `javax.ws.rs.client.*` (client-side API) — Helidon uses a
  different HTTP client API.
- Does not migrate JAX-RS sub-resources (`@Path` on return type) or
  `@BeanParam` patterns.
- Does not configure media type serialisation (Jackson, JSON-B) in the Helidon app.

---

## HealthCheckSkill — order 20, FILE scope

Migrates Dropwizard `HealthCheck` implementations to the Helidon 4 health check API.

**Trigger:** files that import `com.codahale.metrics.health.HealthCheck` or
`io.dropwizard.health.HealthCheck`, or whose supertype is one of those.
**Postcheck:** forbids residual `com.codahale.metrics.health.HealthCheck` imports.

### Transformations

| Before (Dropwizard) | After (Helidon 4) |
|---------------------|-------------------|
| `import com.codahale.metrics.health.HealthCheck` | Removed |
| `import com.codahale.metrics.health.HealthCheck.Result` | Removed |
| `import io.dropwizard.health.HealthCheck` | Removed |
| *(new)* | `import io.helidon.health.HealthCheck` |
| *(new)* | `import io.helidon.health.HealthCheckResponse` |
| `extends HealthCheck` | `implements HealthCheck` |
| `protected Result check() throws Exception {` | `@Override public HealthCheckResponse call() {` |
| `Result.healthy()` | `HealthCheckResponse.builder().status(true).build()` |
| `Result.healthy(message)` | `HealthCheckResponse.builder().status(true).detail("message", message).build()` |
| `Result.unhealthy(message)` | `HealthCheckResponse.builder().status(false).detail("message", message).build()` |

Any remaining `Result.` usage (e.g. stored results, complex builder chains) is
annotated with an inline `DW_MIGRATION_TODO` for manual review.

### What it does NOT do

- Does not register health checks with the Helidon server; that wiring is
  application-specific and must be done manually.
- Does not migrate `HealthCheckRegistry` usage or composite health checks.

---

## DropwizardSourceTypeRegistry

`io.transmute.dw.DropwizardSourceTypeRegistry` implements `SourceTypeRegistry` and
declares FQN pattern coverage for the compile error classifier. All types in the
following namespaces are considered the responsibility of this skill module:

```
io\.dropwizard\..*
com\.codahale\.metrics\..*
javax\.ws\.rs\..*
jakarta\.ws\.rs\..*
javax\.inject\..*
jakarta\.inject\..*
org\.glassfish\.jersey\..*
org\.eclipse\.jetty\..*
org\.hibernate\.validator\..*
io\.dropwizard\.jersey\.params\..*
io\.dropwizard\.jersey\.jsr310\..*
```

If a compile error references a type matching any of these patterns and a skill
previously touched the affected file, the error is classified `SKILL_GAP` and the
responsible skill is re-run on that file. If no skill touched the file the error is
classified `COMPILE_ONLY` and handled by error-triggered skills (order ≥ 200).
Everything else is `NOVEL` and routed to the AI compile-fix agent.

---

## What is covered vs. manual

### Covered (deterministic, no AI needed)

- POM replacement (Helidon 4 parent BOM, core Helidon deps, non-DW dep preservation)
- Bundle transitive dep re-injection (Hibernate, HikariCP, Liquibase, JDBI3, templates)
- JAX-RS HTTP method and path annotations → `@Http.*`
- JAX-RS param annotations → `@Http.PathParam`, `@Http.QueryParam`, `@Http.HeaderParam`
- CDI inject/singleton → `@Service.Inject`, `@Service.Singleton`
- Body parameter detection → `@Http.Entity`
- `@Endpoint` and `@Singleton` added to resource classes
- DW jersey param wrappers → Java types (`.get()` removal)
- `javax.ws.rs.core.Response` → `io.helidon.webserver.http.ServerResponse`
- `@Produces` / `@Consumes` replaced with TODO comments
- Codahale metrics annotations replaced with TODO comments
- `@Auth`, `@UnitOfWork`, `@PermitAll`, `@RolesAllowed` commented out with hints
- `SecurityContext` typed as `Object` with a TODO comment
- DW base class stripping (View, AbstractDAO, Managed, Authenticator, Authorizer)
- DW Bundle import removal
- View template migration (return type `String`, stub return value)
- HealthCheck API migration (interface, method signature, `Result` → builder)

### Requires manual work (TODOs inserted)

- Security integration (Helidon Security auth filters, security context binding)
- Metrics integration (Helidon Micrometer or MicroProfile Metrics)
- `@Context` injection points (JAX-RS context injection has no direct Helidon equivalent)
- `@Produces` / `@Consumes` media type configuration (verify against Helidon defaults)
- `ServerResponse` usage patterns (JAX-RS fluent builder ≠ Helidon response model)
- JAX-RS `@Provider` implementations (exception mappers, request/response filters)
- JAX-RS client code (`javax.ws.rs.client.*`)
- `HealthCheckRegistry` wiring and composite health checks
- Custom Maven plugin configuration in the POM
- Multi-module POM aggregator/parent structure
