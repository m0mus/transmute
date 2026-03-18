---
name: HealthCheck Migration
type: recipe
triggers:
  - imports: [com.codahale.metrics.health.HealthCheck]
  - imports: [io.dropwizard.health.HealthCheck]
order: 20
postchecks:
  forbidImports:
    - com.codahale.metrics.health.HealthCheck
    - io.dropwizard.health.HealthCheck
---

This file extends the Dropwizard/Codahale `HealthCheck` class and must be migrated to
implement the Helidon 4 SE `io.helidon.health.HealthCheck` interface.

## Class declaration

Change:
```java
public class MyHealthCheck extends HealthCheck {
```
To:
```java
public class MyHealthCheck implements io.helidon.health.HealthCheck {
```

## Check method signature

The Dropwizard `check()` method must be renamed and its signature changed:

Change:
```java
@Override
protected Result check() throws Exception {
```
To:
```java
@Override
public HealthCheckResponse call() {
```

## Result factory methods

Replace Codahale `Result` factory methods with the Helidon `HealthCheckResponse` builder:

| Dropwizard | Helidon |
|------------|---------|
| `Result.healthy()` | `HealthCheckResponse.builder().status(true).build()` |
| `Result.healthy("msg")` | `HealthCheckResponse.builder().status(true).detail("message", "msg").build()` |
| `Result.unhealthy("msg")` | `HealthCheckResponse.builder().status(false).detail("message", "msg").build()` |
| `Result.unhealthy(exception)` | `HealthCheckResponse.builder().status(false).detail("message", exception.getMessage()).build()` |

If any other `Result.` usage remains after the above replacements, add a TODO comment:
```java
// DW_MIGRATION_TODO[manual]: was Result.* — adapt to HealthCheckResponse builder
```

## Exception handling

Since the new `call()` method does not declare `throws Exception`, wrap any checked exceptions
that were thrown in the original `check()` body with a try/catch and return an unhealthy response:

```java
try {
    // original logic
} catch (Exception e) {
    return HealthCheckResponse.builder().status(false).detail("message", e.getMessage()).build();
}
```

## Imports

Remove:
- `com.codahale.metrics.health.HealthCheck`
- `com.codahale.metrics.health.HealthCheck.Result`
- `io.dropwizard.health.HealthCheck`

Add:
- `io.helidon.health.HealthCheck`
- `io.helidon.health.HealthCheckResponse`
