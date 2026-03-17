# transmute-dw-helidon

Markdown-based migration module for migrating **Dropwizard 3** projects to
**Helidon 4 SE** (declarative HTTP server, service registry injection).

This module contains **zero Java code** and **zero dependencies** — only
`.recipe.md` and `.feature.md` resource files. It is assembled with
`transmute-core` at runtime by placing both JARs on the classpath.

---

## Migrations overview

Migrations run in this order. Scope and triggers are declared in each file's
front-matter and derived automatically by the planner.

| Order | File | Type | Scope | Trigger |
|-------|------|------|-------|---------|
| 1 | `features/build-migration.feature.md` | feature | PROJECT | `pom.xml`, `build.gradle`, etc. present |
| 5 | `recipes/jaxrs-rest-resource.recipe.md` | recipe | FILE | `@Path` annotation or `javax.ws.rs.` imports |
| 20 | `recipes/health-check.recipe.md` | recipe | FILE | `com.codahale.metrics.health.HealthCheck` / `io.dropwizard.health.HealthCheck` import or supertype |

---

## Build File Migration — order 1, PROJECT scope

`features/build-migration.feature.md`

Triggered when any of `pom.xml`, `build.gradle`, `build.gradle.kts`, or `build.xml`
exists in the project root. Runs before any file-level recipes.

### What it does

1. Detects the build system (Maven, Gradle Kotlin DSL, Gradle Groovy DSL, Ant).
2. Extracts project identity (`groupId`, `artifactId`, `version`, `name`, `mainClass`).
3. Produces a migrated build file using the Helidon 4 SE Maven parent BOM (or
   equivalent Gradle configuration) as the structural base:
   - **Removes** all `io.dropwizard`, `org.glassfish.jersey`, `com.codahale.metrics`,
     `javax.ws.rs`, `jakarta.ws.rs`, and `org.eclipse.jetty` dependencies.
   - **Keeps** all other application dependencies (databases, serialization, utilities).
   - **Keeps** non-Dropwizard `<dependencyManagement>` and `<properties>` entries.
   - Does **not** duplicate dependencies already declared in the Helidon template.
4. Writes the migrated build file back in place.

### Helidon 4 SE Maven template (base)

The feature embeds the following template inline — the AI fills in identity values
and merges preserved dependencies into the `<dependencies>` section:

- `io.helidon.applications:helidon-se` parent BOM (version 4.3.4)
- `helidon-webserver`, `helidon-http-media-jsonb`, `helidon-service-registry`,
  `helidon-config-yaml`, `helidon-logging-jul`
- `helidon-service-maven-plugin` with `create-application` goal
- `helidon-bundles-apt` annotation processor

### What it does NOT do

- Does not handle multi-module POM hierarchies. Only the root build file is processed.
- Does not migrate custom Maven plugin configuration (Shade, Assembly, etc.) —
  review plugin config manually.
- For Gradle / Ant: uses the same logic but with idiomatic syntax for the detected
  build tool.

---

## REST Resource Recipe — order 5, FILE scope

`recipes/jaxrs-rest-resource.recipe.md`

Triggered on Java files that use `@javax.ws.rs.Path` or import from `javax.ws.rs.`.
Runs before HealthCheck migration.
Postcheck: forbids residual `javax.ws.rs.*` or `jakarta.ws.rs.*` imports.

### What it does

**Class-level:**
- Adds `@Http.Endpoint` (import `io.helidon.webserver.http.Http`)
- Adds `@Service.Singleton` (import `io.helidon.service.registry.Service`)
- Removes class-level `@Path` — the path moves to each method annotation

**HTTP method annotations:**

| Remove | Add |
|--------|-----|
| `@GET` + `@Path("/foo")` | `@Http.GET(path = "/foo")` |
| `@POST` + `@Path("/foo")` | `@Http.POST(path = "/foo")` |
| `@PUT` + `@Path("/foo")` | `@Http.PUT(path = "/foo")` |
| `@DELETE` + `@Path("/foo")` | `@Http.DELETE(path = "/foo")` |
| `@PATCH` / `@HEAD` / `@OPTIONS` | equivalent `@Http.*` forms |

**Parameter annotations:**

| Remove | Add |
|--------|-----|
| `@PathParam("x")` | `@Http.PathParam("x")` |
| `@QueryParam("x")` | `@Http.QueryParam("x")` |
| `@HeaderParam("x")` | `@Http.HeaderParam("x")` |
| Body params (no annotation on POST/PUT/PATCH/DELETE) | `@Http.Entity` |

**Dropwizard param wrappers → Java types** (`.get()` calls removed):
`IntParam`, `LongParam`, `BooleanParam`, `FloatParam`, `DoubleParam`, `StringParam`,
`UUIDParam` → `UUID`, `LocalDateParam` → `LocalDate`

**Response type:**
- `javax/jakarta.ws.rs.core.Response` → `void` (for simple ok responses) or
  `ServerResponse` with a `DW_MIGRATION_TODO` comment

**`@Produces` / `@Consumes`:** replaced with `DW_MIGRATION_TODO` comments

**Dropwizard base classes:** `extends`/`implements` clause removed + TODO comment

**Import cleanup:** removes all `javax.ws.rs.*`, `jakarta.ws.rs.*`,
`io.dropwizard.jersey.params.*`, `io.dropwizard.jersey.jsr310.*`

### What it does NOT do

- Does not migrate `@FormParam` (no direct Helidon equivalent).
- Does not migrate JAX-RS `ExceptionMapper`, `ContainerRequestFilter`, `@Provider` types.
- Does not migrate JAX-RS client code (`javax.ws.rs.client.*`).
- Does not migrate `@Context` injection (commented out with TODO by intent).
- Does not touch `@Inject`, metrics annotations, `@Auth`, `@UnitOfWork` —
  those are handled by separate feature skills.

---

## HealthCheck Migration Recipe — order 20, FILE scope

`recipes/health-check.recipe.md`

Triggered on Java files that import or extend `com.codahale.metrics.health.HealthCheck`
or `io.dropwizard.health.HealthCheck`.
Postcheck: forbids residual `com.codahale.metrics.health.HealthCheck` imports.

### Transformations

| Before (Dropwizard) | After (Helidon 4) |
|---------------------|-------------------|
| `import com.codahale.metrics.health.HealthCheck` | Removed |
| `import com.codahale.metrics.health.HealthCheck.Result` | Removed |
| `import io.dropwizard.health.HealthCheck` | Removed |
| *(new)* | `import io.helidon.health.HealthCheck` |
| *(new)* | `import io.helidon.health.HealthCheckResponse` |
| `extends HealthCheck` | `implements io.helidon.health.HealthCheck` |
| `protected Result check() throws Exception {` | `@Override public HealthCheckResponse call() {` |
| `Result.healthy()` | `HealthCheckResponse.builder().status(true).build()` |
| `Result.healthy(message)` | `HealthCheckResponse.builder().status(true).detail("message", message).build()` |
| `Result.unhealthy(message)` | `HealthCheckResponse.builder().status(false).detail("message", message).build()` |
| `Result.unhealthy(exception)` | `HealthCheckResponse.builder().status(false).detail("message", ex.getMessage()).build()` |

Checked exceptions formerly declared on `check()` are wrapped in try/catch returning
an unhealthy response (since `call()` does not declare `throws Exception`).

Any other `Result.` usage is annotated with `DW_MIGRATION_TODO[manual]`.

### What it does NOT do

- Does not register health checks with the Helidon server — wiring is application-specific.
- Does not migrate `HealthCheckRegistry` or composite health checks.

---

## What is covered vs. requires manual work

### Covered

- Build file migration (Maven / Gradle / Ant → Helidon 4 SE)
- JAX-RS HTTP method and path annotations → `@Http.*`
- JAX-RS param annotations → `@Http.PathParam / QueryParam / HeaderParam`
- Body parameter detection → `@Http.Entity`
- `@Http.Endpoint` + `@Service.Singleton` added to resource classes
- DW Jersey param wrappers → Java types (`.get()` removal)
- `javax.ws.rs.core.Response` → `void` / `ServerResponse`
- `@Produces` / `@Consumes` replaced with TODO comments
- DW base class stripping (View, AbstractDAO, Managed, etc.)
- Bundle import removal
- HealthCheck API migration (interface, method signature, `Result` → builder)

### Requires manual work (TODOs inserted)

- Security integration (`@Auth`, `SecurityContext`, `@RolesAllowed`, `@PermitAll`)
- Metrics integration (`@Timed`, `MetricRegistry`, etc.)
- `@Context` injection points
- `@Produces` / `@Consumes` media type configuration
- `ServerResponse` usage patterns
- JAX-RS `@Provider` implementations (exception mappers, filters)
- JAX-RS client code (`javax.ws.rs.client.*`)
- `HealthCheckRegistry` wiring and composite health checks
- Custom Maven plugin configuration
- Multi-module POM aggregator/parent structure
