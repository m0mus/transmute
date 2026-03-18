# transmute-dw-helidon

Markdown-based migration module for migrating **Dropwizard 3** projects to
**Helidon 4 SE** (declarative HTTP server, service registry injection).

This module contains **zero Java code** and **zero dependencies** — only
`.recipe.md` and `.feature.md` resource files. It is assembled with
`transmute-core` at runtime by placing both JARs on the classpath.

---

## Migrations overview

Migrations run in the order shown. Triggers are declared in each file's
front-matter; scope is derived automatically by the planner.

| Order | File | Type | Scope | What it migrates |
|-------|------|------|-------|-----------------|
| 1 | `recipes/build-migration.recipe.md` | recipe | FILE | `pom.xml` / Gradle / Ant → Helidon 4 SE |
| 2 | `recipes/application-bootstrap.recipe.md` | recipe | FILE | `Application<T>` → `Main` class |
| 3 | `recipes/dropwizard-configuration.recipe.md` | recipe | FILE | `Configuration` subclass → Helidon `Config` accessor |
| 5 | `recipes/jaxrs-rest-resource.recipe.md` | recipe | FILE | JAX-RS `@Path` resources → `@Http.Endpoint` |
| 6 | `recipes/exception-mapper.recipe.md` | recipe | FILE | `ExceptionMapper<E>` → error handler TODO |
| 10 | `recipes/managed-lifecycle.recipe.md` | recipe | FILE | `Managed` → `@Service.PostConstruct`/`@Service.PreDestroy` |
| 12 | `recipes/http-client.recipe.md` | recipe | FILE | `JerseyClientBuilder`/`HttpClientBuilder` → WebClient TODOs |
| 15 | `recipes/metrics-migration.recipe.md` | recipe | FILE | Codahale metrics → Micrometer |
| 20 | `recipes/health-check.recipe.md` | recipe | FILE | DW `HealthCheck` → Helidon `HealthCheck` |
| 50 | `features/injection-migration.feature.md` | feature | FILE | Guice/HK2/`javax.inject` → Service Registry |
| 50 | `features/unit-of-work.feature.md` | feature | FILE | `@UnitOfWork`/`AbstractDAO` → DbClient TODOs |
| 50 | `features/security-auth.feature.md` | feature | FILE | `@Auth`/`Authenticator`/`Authorizer` → Security TODOs |

Features have no explicit `order:` set and therefore use the default of 50. They run
after all recipes with lower order values. Multiple features can compose with one recipe
in a single AI call when they target the same file.

---

## Build File Migration — order 1, FILE scope

`recipes/build-migration.recipe.md`

Triggered when any of `pom.xml`, `build.gradle`, `build.gradle.kts`, or `build.xml`
exists in the project root. Runs before any Java-level recipes.

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

The recipe embeds the following template inline — the AI fills in identity values
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

## Application Bootstrap — order 2, FILE scope

`recipes/application-bootstrap.recipe.md`

Triggered on files that extend `io.dropwizard.Application` or `io.dropwizard.core.Application`.
Postchecks: forbids residual `io.dropwizard.Application`, `Bootstrap`, and `Environment` imports.

### What it does

1. Renames the class to `Main` and rewrites the body as a Helidon 4 SE main class using
   `WebServer.builder()` / `HttpRouting.Builder`.
2. Updates `<mainClass>` in `pom.xml` to the renamed class.
3. Adds `application.yaml` under `src/main/resources/` if absent (`server.port: 8080`).
4. Deletes the old Dropwizard root-level YAML config file (`config.yml`, `dev.yml`, etc.).
5. Adds TODO comments for bundle registrations and manually-wired lifecycle services.

---

## Dropwizard Configuration — order 3, FILE scope

`recipes/dropwizard-configuration.recipe.md`

Triggered on files that extend `io.dropwizard.core.Configuration` or `io.dropwizard.Configuration`.
Postchecks: forbids residual `io.dropwizard.core.Configuration` / `io.dropwizard.Configuration` imports.

### What it does

- Removes `extends Configuration` from the class declaration.
- Adds a `Config`-backed constructor and a `static create()` factory method.
- Converts simple scalar fields to `config.get(key).asString()/.asInt()/...` accessor methods.
- Replaces complex/nested Dropwizard factory fields (e.g. `DatabaseFactory`) with TODO comments.
- Removes Jackson (`@JsonProperty`, `@JsonIgnore`) and validation (`@NotNull`, `@Valid`) annotations.
- Replaces all `io.dropwizard.*` and `javax/jakarta.validation.*` imports with `io.helidon.config.Config`.

---

## REST Resource — order 5, FILE scope

`recipes/jaxrs-rest-resource.recipe.md`

Triggered on Java files that use `@javax.ws.rs.Path` or import from `javax.ws.rs.`.
Postchecks: forbids residual `javax.ws.rs.*` or `jakarta.ws.rs.*` imports.

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
| `@FormParam("x")` | `@Http.FormParam("x")` (with TODO: verify form handling) |
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

- Does not migrate `@Context` injection (commented out with TODO by intent).
- Does not touch `@Inject`, metrics annotations, `@Auth`, `@UnitOfWork` —
  those are handled by separate feature skills.

---

## Exception Mapper Migration — order 6, FILE scope

`recipes/exception-mapper.recipe.md`

Triggered on files that extend `javax.ws.rs.ext.ExceptionMapper` or `jakarta.ws.rs.ext.ExceptionMapper`.
Postchecks: forbids residual `ExceptionMapper` imports.

### What it does

- Removes `implements ExceptionMapper<E>` and `@Provider`.
- Adds `@Service.Singleton` and `@Http.Endpoint` to the class.
- Replaces the `toResponse()` method with a `DW_MIGRATION_TODO` comment showing the Helidon
  `routing.error(E.class, ...)` wiring pattern.
- If the class becomes empty, retains the class shell with a class-level TODO comment.

---

## Managed Lifecycle — order 10, FILE scope

`recipes/managed-lifecycle.recipe.md`

Triggered on files that import **and** extend `io.dropwizard.lifecycle.Managed`.
Postchecks: forbids residual `io.dropwizard.lifecycle.Managed` imports.

### What it does

- Removes `implements Managed`.
- Adds `@Service.Singleton` if not already present.
- Renames `start()` → `@Service.PostConstruct`, `stop()` → `@Service.PreDestroy`.
- Removes `@Override` and `throws Exception` from both methods; wraps checked-exception
  calls in `try/catch` where needed.

---

## HTTP Client Migration — order 12, FILE scope

`recipes/http-client.recipe.md`

Triggered on files importing from `io.dropwizard.client`.
Postchecks: forbids residual `io.dropwizard.client` imports.

### What it does

Replaces `JerseyClientBuilder`/`HttpClientBuilder` constructor calls, `WebTarget`/`Response`
call chains, and `JerseyClientConfiguration`/`HttpClientConfiguration` fields with
`DW_MIGRATION_TODO` comments pointing to the Helidon `WebClient` API. Does not add WebClient
imports automatically — the developer completes the migration after reviewing the TODOs.

---

## Metrics Migration — order 15, FILE scope

`recipes/metrics-migration.recipe.md`

Triggered on files importing from `com.codahale.metrics` or `io.dropwizard.metrics`.
Postchecks: forbids residual Codahale/Dropwizard metrics imports.

### What it does

| Before | After |
|--------|-------|
| `@Timed` (Codahale) | `@io.micrometer.core.annotation.Timed` |
| `@Counted` (Codahale) | `@io.micrometer.core.annotation.Counted` |
| `@Metered` | `@Counted` + TODO comment |
| `@ExceptionMetered` | Removed + TODO comment |
| `MetricRegistry` field | `MeterRegistry` field (`@Service.Inject`) |
| `metrics.timer(name(...))` | `Timer.builder("...").register(meterRegistry)` |
| `metrics.counter(name(...))` | `Counter.builder("...").register(meterRegistry)` |
| `metrics.histogram(name(...))` | `DistributionSummary.builder("...").register(meterRegistry)` |
| `metrics.gauge(name, obj, fn)` | `Gauge.builder("...", obj, fn).register(meterRegistry)` |
| `timer.update(duration, unit)` | `timer.record(duration, unit)` |
| `counter.inc()` | `counter.increment()` |

---

## HealthCheck Migration — order 20, FILE scope

`recipes/health-check.recipe.md`

Triggered on Java files that import or extend `com.codahale.metrics.health.HealthCheck`
or `io.dropwizard.health.HealthCheck`.
Postcheck: forbids residual `com.codahale.metrics.health.HealthCheck` imports.

### Transformations

| Before (Dropwizard) | After (Helidon 4) |
|---------------------|-------------------|
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

## Injection Migration — feature, FILE scope (default order 50)

`features/injection-migration.feature.md`

Triggered on files importing from `com.google.inject`, `org.glassfish.hk2`, `org.jvnet.hk2`,
`javax.inject`, or `jakarta.inject`. Postchecks: forbids all those imports.

### What it does

| Before | After |
|--------|-------|
| `@Singleton` (Guice/javax/jakarta) | `@Service.Singleton` |
| `@Inject` (Guice/javax/jakarta) | `@Service.Inject` |
| `@Named("x")` (any package) | `@Service.Named("x")` |
| `@Provides` (Guice) | TODO comment to create `@Service.Singleton` factory class |
| `@org.jvnet.hk2.annotations.Service` | `@Service.Singleton` |
| `@org.glassfish.hk2.api.Contract` | Removed |
| `@org.glassfish.hk2.api.Immediate` | `@Service.Singleton` |
| `@org.glassfish.hk2.api.PerLookup` | TODO comment |
| Guice `AbstractModule` subclass | Class-level TODO; body left for manual review |
| `GuiceBundle` / `GuiceApplicationBundle` | TODO comment per usage |

---

## Unit of Work / Hibernate Migration — feature, FILE scope (default order 50)

`features/unit-of-work.feature.md`

Triggered on files importing from `io.dropwizard.hibernate` or `io.dropwizard.db`.
Postchecks: forbids those imports.

Replaces `@UnitOfWork`, `AbstractDAO<T>`, `HibernateBundle`, `DBIFactory`/`JdbiFactory`,
and `SessionFactory` usages with `DW_MIGRATION_TODO` comments pointing to the Helidon
`DbClient` API. Does not add DbClient imports automatically.

---

## Security / Auth Migration — feature, FILE scope (default order 50)

`features/security-auth.feature.md`

Triggered on files importing from `io.dropwizard.auth` or using `@io.dropwizard.auth.Auth`.
Postchecks: forbids `io.dropwizard.auth` imports.

Replaces `@Auth` parameters, `Authenticator<C,P>`, `Authorizer<P>`, and `UnauthorizedHandler`
implementations with `DW_MIGRATION_TODO` comments pointing to Helidon Security. The original
`authenticate()` / `authorize()` methods are wrapped in a block comment for developer
reference so that all `io.dropwizard.auth.*` imports can be cleanly removed. Does not add
Helidon Security imports automatically.

---

## Integration tests

Recipe integration tests apply each migration to real Dropwizard source files from the
[dropwizard-example](https://github.com/dropwizard/dropwizard/tree/master/dropwizard-example)
project and verify postchecks pass.

Tests require a real AI model and are skipped automatically when `TRANSMUTE_API_KEY`
is not set:

```powershell
$env:TRANSMUTE_API_KEY = "sk-..."
mvn clean test --also-make -pl transmute-dw-helidon "-Dsurefire.failIfNoSpecifiedTests=false"
```

Fixture files are in `src/test/resources/fixtures/`. The test harness (`RecipeTestHarness`)
loads recipes from the module's own classpath, so it always tests the current recipe files.

---

## What is covered vs. requires manual work

### Automatically migrated

- Build file (Maven / Gradle / Ant → Helidon 4 SE BOM)
- Application main class (`Application<T>` → `Main` with `WebServer`)
- Configuration class (`extends Configuration` → Helidon `Config` accessor for scalar fields)
- JAX-RS HTTP method and path annotations → `@Http.*`
- JAX-RS param annotations → `@Http.PathParam / QueryParam / HeaderParam / FormParam`
- Body parameter detection → `@Http.Entity`
- `@Http.Endpoint` + `@Service.Singleton` added to resource classes
- DW Jersey param wrappers → Java types (`.get()` removal)
- `javax.ws.rs.core.Response` → `void` / `ServerResponse`
- Bundle import removal
- `ExceptionMapper<E>` → `@Service.Singleton` shell + routing TODO
- `Managed.start()`/`stop()` → `@Service.PostConstruct`/`@Service.PreDestroy`
- Codahale metrics annotations and `MetricRegistry` → Micrometer equivalents
- HealthCheck API (interface, method signature, `Result` → builder)
- Guice / HK2 / `javax.inject` annotations → Service Registry annotations

### Requires manual work (TODOs inserted)

- Complex/nested `Configuration` fields (e.g. `DatabaseFactory`, `HttpClientConfiguration`)
- Exception mapper routing wiring (`Main.routing().error(...)`)
- HTTP client migration (Dropwizard → Helidon `WebClient`)
- `@UnitOfWork` / `AbstractDAO` / `HibernateBundle` → Helidon `DbClient`
- Security integration (`@Auth`, `Authenticator`, `Authorizer` → Helidon Security)
- `@Produces` / `@Consumes` media type configuration
- `@Context` injection points
- `ServerResponse` usage patterns
- `HealthCheckRegistry` wiring and composite health checks
- Custom Maven plugin configuration (Shade, Assembly, etc.)
- Multi-module POM aggregator/parent structure
