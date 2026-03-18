---
name: REST Resource
type: recipe
triggers:
  - annotations: [javax.ws.rs.Path]
  - imports: [javax.ws.rs.]
order: 5
postchecks:
  forbidImports:
    - javax.ws.rs.
    - jakarta.ws.rs.
---

This file is a JAX-RS REST resource that must be migrated to Helidon 4 SE using the
declarative HTTP annotations from `io.helidon.http.Http` and the service registry.

## Class-level changes

1. Add `@RestServer.Endpoint` to the class (import `io.helidon.webserver.http.RestServer`).
2. Add `@Service.Singleton` to the class (import `io.helidon.service.registry.Service`).
3. Replace `@Path("/foo")` on the class with `@Http.Path("/foo")` (import `io.helidon.http.Http`).

## HTTP method annotations

Replace JAX-RS HTTP method annotations with Helidon equivalents.
Path is specified with a **separate** `@Http.Path` annotation — NOT as an attribute of the method annotation.

| Remove | Add |
|--------|-----|
| `@GET` + `@Path("/foo")` | `@Http.GET` + `@Http.Path("/foo")` |
| `@POST` + `@Path("/foo")` | `@Http.POST` + `@Http.Path("/foo")` |
| `@PUT` + `@Path("/foo")` | `@Http.PUT` + `@Http.Path("/foo")` |
| `@DELETE` + `@Path("/foo")` | `@Http.DELETE` + `@Http.Path("/foo")` |
| `@PATCH` + `@Path("/foo")` | `@Http.PATCH` + `@Http.Path("/foo")` |
| `@HEAD` + `@Path("/foo")` | `@Http.HEAD` + `@Http.Path("/foo")` |
| `@OPTIONS` + `@Path("/foo")` | `@Http.OPTIONS` + `@Http.Path("/foo")` |

If a method has an HTTP annotation but no `@Path`, use `@Http.GET` (etc.) without `@Http.Path`.

**IMPORTANT:** `@Http.GET`, `@Http.POST`, etc. take NO arguments. Path is ALWAYS a separate `@Http.Path` annotation.

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
  // DW_MIGRATION_TODO[manual]: was Response -- adapt to ServerResponse / Helidon response API
  ```
- Import `io.helidon.webserver.http.ServerResponse`.

## @Produces / @Consumes

**IMPORTANT:** Helidon's `@Http.Produces` and `@Http.Consumes` target METHOD only, not TYPE.
If the original `@Produces`/`@Consumes` is on the class, move it to each individual HTTP method.

Replace JAX-RS `@Produces` and `@Consumes` annotations with their Helidon equivalents:

| Remove | Add |
|--------|-----|
| `@Produces(MediaType.APPLICATION_JSON)` | `@Http.Produces("application/json")` |
| `@Consumes(MediaType.APPLICATION_JSON)` | `@Http.Consumes("application/json")` |
| `@Produces(MediaType.TEXT_PLAIN)` | `@Http.Produces("text/plain")` |

Use string literals for media types. Place `@Http.Produces`/`@Http.Consumes` on each method, NOT on the class.

## Auth-injected parameters

Helidon declarative HTTP methods only accept parameters with registered parameter handlers
(`@Http.PathParam`, `@Http.QueryParam`, `@Http.HeaderParam`, `@Http.Entity`, etc.).
Unrecognized parameter types cause a compile-time `CodegenException`.

For any method parameter annotated with `@Auth` (from `io.dropwizard.auth`), or any
parameter of a Dropwizard auth type (`User`, `Principal`, `SecurityContext`, or any type
used as the auth principal), **remove the parameter entirely** from the method signature.
Add a TODO comment above the method:

```java
// DW_MIGRATION_TODO[manual]: @Auth User parameter removed -- inject via Helidon Security
//   See: https://helidon.io/docs/v4/se/security
@Http.GET
@Http.Path("/secret")
public String showSecret() {
    // DW_MIGRATION_TODO: obtain authenticated user from Helidon SecurityContext
```

If the method body references the removed parameter, replace those usages with a
placeholder or TODO comment so the code compiles:
- For string formatting: use a placeholder like `"anonymous"`
- For method calls on the removed object: comment them out with a TODO

## Context-injected parameters

Similarly, remove any parameter annotated with `@Context` (from `javax.ws.rs.core`) or
any `SecurityContext`, `HttpServletRequest`, `HttpServletResponse`, `UriInfo` parameter.
These have no direct equivalent in Helidon declarative HTTP. Add a TODO comment.

## Dropwizard base class stripping

If the class extends a Dropwizard class (e.g., `View`, `AbstractDAO`) or implements a
Dropwizard interface (`Managed`, `Authenticator`, `Authorizer`, `UnauthorizedHandler`),
remove the `extends`/`implements` clause and add a TODO comment on the class declaration line:
```java
// DW_MIGRATION_TODO[manual]: was extends/implements <ClassName> -- implement Helidon equivalent
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
- `io.helidon.http.Http` (for `@Http.GET`, `@Http.Path`, `@Http.PathParam`, etc.)
- `io.helidon.webserver.http.RestServer` (for `@RestServer.Endpoint`)
- `io.helidon.service.registry.Service` (for `@Service.Singleton`)
- Any other Helidon imports needed based on what is used.

## DO NOT touch

Do NOT modify anything related to:
- `@Inject`, `@Named`, Guice `Injector` or `Module` -- handled by the Injection feature.
- `@Timed`, `@Metered`, `MetricRegistry` -- handled by the Metrics Migration recipe.
