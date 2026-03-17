---
name: REST Resource
type: recipe
triggers:
  - annotations: [javax.ws.rs.Path]
  - imports: [javax.ws.rs.]
order: 5
after: [Build File Migration]
postchecks:
  forbidImports:
    - javax.ws.rs.
    - jakarta.ws.rs.
---

This file is a JAX-RS REST resource that must be migrated to Helidon 4 SE using the
declarative HTTP annotations from `io.helidon.http.Http` and the service registry.

## Class-level changes

1. Add `@Http.Endpoint` to the class (import `io.helidon.webserver.http.Http`).
2. Add `@Service.Singleton` to the class (import `io.helidon.service.registry.Service`).
3. Remove `@Path` from the class — the Helidon path is expressed per-method via `@Http.GET(path=...)` etc.

## HTTP method annotations

Replace all JAX-RS HTTP method + `@Path` combinations:

| Remove | Add |
|--------|-----|
| `@GET` + `@Path("/foo")` | `@Http.GET(path = "/foo")` |
| `@POST` + `@Path("/foo")` | `@Http.POST(path = "/foo")` |
| `@PUT` + `@Path("/foo")` | `@Http.PUT(path = "/foo")` |
| `@DELETE` + `@Path("/foo")` | `@Http.DELETE(path = "/foo")` |
| `@PATCH` + `@Path("/foo")` | `@Http.PATCH(path = "/foo")` |
| `@HEAD` + `@Path("/foo")` | `@Http.HEAD(path = "/foo")` |
| `@OPTIONS` + `@Path("/foo")` | `@Http.OPTIONS(path = "/foo")` |

If a method has an HTTP annotation but no `@Path`, use `@Http.GET` (etc.) without a `path` argument.

## Parameter annotations

| Remove | Add |
|--------|-----|
| `@PathParam("x")` | `@Http.PathParam("x")` |
| `@QueryParam("x")` | `@Http.QueryParam("x")` |
| `@HeaderParam("x")` | `@Http.HeaderParam("x")` |
| `@FormParam("x")` | `@Http.FormParam("x")` (add TODO: verify form handling) |

For POST/PUT/PATCH/DELETE methods, any parameter that has no annotation should receive
`@Http.Entity` (body parameter). Do not add `@Http.Entity` if the param already has a
`@PathParam`, `@QueryParam`, `@HeaderParam`, or `@FormParam` annotation.

## Dropwizard param wrapper types

Replace Dropwizard Jersey param wrappers with plain Java types. Also remove any `.get()` calls
that were used to unwrap them.

| Remove type | Replace with | Import |
|-------------|-------------|--------|
| `IntParam` | `Integer` | (none needed) |
| `LongParam` | `Long` | (none needed) |
| `BooleanParam` | `Boolean` | (none needed) |
| `FloatParam` | `Float` | (none needed) |
| `DoubleParam` | `Double` | (none needed) |
| `StringParam` | `String` | (none needed) |
| `UUIDParam` | `UUID` | `java.util.UUID` |
| `LocalDateParam` | `LocalDate` | `java.time.LocalDate` |

## Response type

Replace `javax.ws.rs.core.Response` / `jakarta.ws.rs.core.Response` return types:

- Change method return type to `void` if the only usage is `Response.ok().build()` or similar simple success response.
- Otherwise change return type to `ServerResponse` and add a TODO comment:
  ```java
  // DW_MIGRATION_TODO[manual]: was Response — adapt to ServerResponse / Helidon response API
  ```
- Import `io.helidon.webserver.http.ServerResponse`.

## @Produces / @Consumes

Remove `@Produces` and `@Consumes` annotations and replace each with a TODO comment on the same line:
```java
// DW_MIGRATION_TODO[manual]: was @Produces / @Consumes — configure content negotiation in Helidon routing
```

## Dropwizard base class stripping

If the class extends a Dropwizard class (e.g., `View`, `AbstractDAO`) or implements a
Dropwizard interface (`Managed`, `Authenticator`, `Authorizer`, `UnauthorizedHandler`),
remove the `extends`/`implements` clause and add a TODO comment on the class declaration line:
```java
// DW_MIGRATION_TODO[manual]: was extends/implements <ClassName> — implement Helidon equivalent
```

## Bundle imports

Remove any imports of `io.dropwizard.Bundle` or `io.dropwizard.ConfiguredBundle` silently.

## Imports cleanup

Remove all imports from:
- `javax.ws.rs.*`
- `jakarta.ws.rs.*`
- `io.dropwizard.jersey.params.*`
- `io.dropwizard.jersey.jsr310.*`

Add required Helidon imports:
- `io.helidon.webserver.http.Http` (for `@Http.Endpoint`, `@Http.GET`, etc.)
- `io.helidon.service.registry.Service` (for `@Service.Singleton`)
- Any other Helidon imports needed based on what is used.

## DO NOT touch

Do NOT modify anything related to:
- `@Inject`, `@Named`, Guice `Injector` or `Module` — handled by the Injection feature skill.
- `@Timed`, `@Metered`, `MetricRegistry` — handled by the Metrics feature skill.
- `@Auth`, `SecurityContext` — handled by the Security feature skill.
- `@UnitOfWork` — handled by the Persistence feature skill.
