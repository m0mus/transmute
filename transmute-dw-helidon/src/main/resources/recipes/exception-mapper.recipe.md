---
name: Exception Mapper Migration
type: recipe
order: 6
triggers:
  - superTypes: [javax.ws.rs.ext.ExceptionMapper]
  - superTypes: [jakarta.ws.rs.ext.ExceptionMapper]
postchecks:
  forbidImports:
    - javax.ws.rs.ext.ExceptionMapper
    - jakarta.ws.rs.ext.ExceptionMapper
---

This file implements the JAX-RS `ExceptionMapper<E>` interface and must be migrated to a
Helidon 4 SE service. In Helidon SE, exception handling is registered on the routing builder
rather than via a provider contract.

## Class declaration

Remove `implements ExceptionMapper<E>` from the class declaration (including the type parameter).

Remove the `@Provider` annotation if present.

Add `@Service.Singleton` to the class.

Add `@RestServer.Endpoint` to the class so that it is discoverable as an HTTP service component (import `io.helidon.webserver.http.RestServer`).

## toResponse() method

The `toResponse(E exception)` method cannot be directly retained — Helidon SE does not call
this method automatically. Preserve the method body as a `TRANSMUTE[manual]` comment so the developer can
wire it into the routing:

```java
// TRANSMUTE[manual]: was ExceptionMapper<E> toResponse() — register as error handler in routing:
// routing.error(E.class, (req, res, ex) -> { /* original toResponse logic here */ });
// Consider moving this logic to Main.routing() instead of keeping this class.
```

Where `E` is the actual exception type that was the type argument of `ExceptionMapper<E>`.

If the `toResponse` method has a simple body (e.g., return a fixed status and entity), translate
the comment to show the Helidon equivalent inline:

```java
// TRANSMUTE[manual]: was ExceptionMapper<NotFoundException> — register in Main.routing():
// routing.error(NotFoundException.class, (req, res, ex) -> res.status(404).send(ex.getMessage()));
```

After adding the `TRANSMUTE[manual]` comment, remove the `toResponse()` method body and signature entirely
(do not keep an empty or broken method).

If the class becomes empty after the removal, keep the class declaration with only the
`@Service.Singleton` and `@RestServer.Endpoint` annotations plus a class-level `TRANSMUTE[manual]` comment:

```java
// TRANSMUTE[manual]: was ExceptionMapper<E> — class body removed; wire error handling in Main.routing()
```

## Imports

Remove:
- `javax.ws.rs.ext.ExceptionMapper`
- `jakarta.ws.rs.ext.ExceptionMapper`
- `javax.ws.rs.ext.Provider`
- `jakarta.ws.rs.ext.Provider`
- `javax.ws.rs.core.Response`
- `jakarta.ws.rs.core.Response`
- Any other `javax.ws.rs.*` or `jakarta.ws.rs.*` imports

Add:
- `io.helidon.service.registry.Service`
- `io.helidon.webserver.http.RestServer`

## DO NOT touch

Do NOT modify anything related to:
- `@Inject`, `@Named`, Guice `Injector` or `Module` — handled by the Injection feature.
- `@Timed`, `@Metered`, `MetricRegistry` — handled by the Metrics Migration recipe.
