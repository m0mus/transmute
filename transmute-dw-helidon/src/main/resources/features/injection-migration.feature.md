---
name: Injection Migration
type: feature
triggers:
  - imports: [com.google.inject]
  - imports: [org.glassfish.hk2]
  - imports: [org.jvnet.hk2]
postchecks:
  forbidImports:
    - com.google.inject
    - org.glassfish.hk2
    - org.jvnet.hk2
---

This file uses Guice or HK2 dependency injection. Migrate all injection annotations and
usages to the Helidon 4 SE Service Registry.

## Annotation mapping

Replace injection annotations according to this table:

| Before | After |
|--------|-------|
| `@com.google.inject.Singleton` | `@Service.Singleton` |
| `@javax.inject.Singleton` | `@Service.Singleton` |
| `@jakarta.inject.Singleton` | `@Service.Singleton` |
| `@com.google.inject.Inject` | `@Service.Inject` |
| `@javax.inject.Inject` | `@Service.Inject` |
| `@jakarta.inject.Inject` | `@Service.Inject` |
| `@Named("x")` (any package) | `@Service.Named("x")` |
| `@com.google.inject.Provides` | `// TODO: create @Service.Singleton factory class` |

Apply replacements to field, constructor, and method injection sites.

## Guice module classes

If the file extends `AbstractModule` or `com.google.inject.Module`:

1. Add a class-level TODO comment:
   ```java
   // TODO: remove Guice module — service registry auto-discovers @Service.Singleton classes
   ```
2. Do **not** delete the class body automatically — leave it for manual review.

If the class registers or uses `GuiceBundle` or `GuiceApplicationBundle`:
- Add a TODO comment on the relevant line:
  ```java
  // TODO: remove GuiceBundle — Helidon service registry replaces Guice DI
  ```

## HK2 annotations

| Before | After |
|--------|-------|
| `@org.jvnet.hk2.annotations.Service` | `@Service.Singleton` |
| `@org.glassfish.hk2.api.Contract` | Remove — implementing the interface is sufficient |
| `@org.glassfish.hk2.api.Immediate` | `@Service.Singleton` |
| `@org.glassfish.hk2.api.PerLookup` | `// TODO: PerLookup has no direct equivalent; review lifecycle` |

## Imports

Remove:
- `com.google.inject.*`
- `org.glassfish.hk2.*`
- `org.jvnet.hk2.*`
- `javax.inject.*`
- `jakarta.inject.*`

Add:
- `io.helidon.service.registry.Service`
