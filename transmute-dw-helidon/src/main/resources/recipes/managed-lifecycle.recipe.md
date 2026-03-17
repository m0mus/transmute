---
name: Managed Lifecycle
type: recipe
triggers:
  - imports: [io.dropwizard.lifecycle.Managed]
    superTypes: [io.dropwizard.lifecycle.Managed]
order: 10
postchecks:
  forbidImports:
    - io.dropwizard.lifecycle.Managed
---

This file implements the Dropwizard `Managed` interface and must be migrated to use
Helidon 4 SE Service Registry lifecycle annotations.

## Class declaration

Remove `implements Managed` from the class declaration.

Add `@Service.Singleton` to the class if it is not already annotated with it.

## Method migration

### start() method

Change:
```java
@Override
public void start() throws Exception {
    pool.start();
}
```
To:
```java
@Service.PostConstruct
public void start() {
    pool.start();
}
```

- Remove `@Override`.
- Add `@Service.PostConstruct`.
- Remove `throws Exception` from the method signature.
- If the method body contains calls that declare checked exceptions, wrap the body:
  ```java
  @Service.PostConstruct
  public void start() {
      try {
          pool.start();
      } catch (Exception e) {
          throw new RuntimeException(e);
      }
  }
  ```

### stop() method

Apply the same transformation to `stop()`:
- Remove `@Override`.
- Add `@Service.PreDestroy`.
- Remove `throws Exception`.
- Wrap checked-exception calls in `try/catch` if needed.

## Imports

Remove:
- `io.dropwizard.lifecycle.Managed`

Add:
- `io.helidon.service.registry.Service`
