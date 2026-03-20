---
name: Unit of Work / Hibernate Migration
type: feature
triggers:
  - imports: [io.dropwizard.hibernate]
  - imports: [io.dropwizard.db]
owns:
  annotations:
    - io.dropwizard.hibernate.UnitOfWork
  types:
    - io.dropwizard.hibernate.AbstractDAO
    - io.dropwizard.hibernate.HibernateBundle
    - io.dropwizard.db.DBIFactory
    - io.dropwizard.jdbi3.JdbiFactory
    - org.hibernate.SessionFactory
postchecks:
  forbidImports:
    - io.dropwizard.hibernate
    - io.dropwizard.db
---

This file uses Dropwizard Hibernate or JDBI/JDNI data access patterns (`@UnitOfWork`,
`AbstractDAO`, `HibernateBundle`, `DBIFactory`, etc.) that must be migrated to Helidon 4 SE
using the Helidon DB Client (`io.helidon.dbclient.DbClient`).

## @UnitOfWork annotation

**IMPORTANT:** For every method that has a `@UnitOfWork` annotation on it, you MUST:
1. Delete the `@UnitOfWork` line from the method.
2. Insert a `TRANSMUTE[manual]` comment on the line directly above the method signature.
3. Remove the `import io.dropwizard.hibernate.UnitOfWork` import line.

Example — before:
```java
@POST
@UnitOfWork
public Person createPerson(@Valid Person person) {
    return peopleDAO.create(person);
}

@GET
@UnitOfWork
public List<Person> listPeople() {
    return peopleDAO.findAll();
}
```

Example — after:
```java
@POST
// TRANSMUTE[manual]: was @UnitOfWork — manage transaction manually via DbClient
public Person createPerson(@Valid Person person) {
    return peopleDAO.create(person);
}

@GET
// TRANSMUTE[manual]: was @UnitOfWork — manage transaction manually via DbClient
public List<Person> listPeople() {
    return peopleDAO.findAll();
}
```

## AbstractDAO subclasses

If the class extends `AbstractDAO<T>`:

1. Remove `extends AbstractDAO<T>` from the class declaration.
2. Add `@Service.Singleton` to the class if not already present.
3. Add a class-level `TRANSMUTE[manual]` comment:
   ```java
   // TRANSMUTE[manual]: was AbstractDAO<T> — replace with io.helidon.dbclient.DbClient injection
   ```
4. Replace each `currentSession()` call with a `TRANSMUTE[manual]` comment on the same line:
   ```java
   // TRANSMUTE[manual]: was currentSession() — use @Service.Inject DbClient dbClient
   ```
5. Replace each `list(query)` call with a `TRANSMUTE[manual]` comment on the same line:
   ```java
   // TRANSMUTE[manual]: was list(query) — use dbClient.execute().namedQuery("...").execute()
   ```
6. Replace each `uniqueResult(query)` call with a `TRANSMUTE[manual]` comment on the same line:
   ```java
   // TRANSMUTE[manual]: was uniqueResult(query) — use dbClient.execute().namedGet("...").execute()
   ```
7. Replace each `persist(entity)` call with a `TRANSMUTE[manual]` comment on the same line:
   ```java
   // TRANSMUTE[manual]: was persist(entity) — use dbClient.execute().createNamedInsert("...").execute()
   ```

If any other `AbstractDAO`-inherited method call cannot be mapped, add a generic `TRANSMUTE[manual]` marker:
```java
// TRANSMUTE[manual]: was AbstractDAO method — adapt to Helidon DbClient API
```

## HibernateBundle / DBIFactory / JdbiFactory in Application class

If this file is the Dropwizard `Application` class (or any class that constructs or configures
`HibernateBundle`, `DBIFactory`, or `JdbiFactory`), add a `TRANSMUTE[manual]` comment at each usage site:

```java
// TRANSMUTE[manual]: was HibernateBundle — configure io.helidon.dbclient.DbClient via application.yaml
// See: https://helidon.io/docs/v4/se/dbclient
```

```java
// TRANSMUTE[manual]: was DBIFactory/JdbiFactory — configure io.helidon.dbclient.DbClient via application.yaml
// See: https://helidon.io/docs/v4/se/dbclient
```

Remove the bundle/factory field declarations and constructor/initializer calls after adding the
`TRANSMUTE[manual]` comments.

## SessionFactory injection

If the class injects or receives a `SessionFactory` parameter:

1. Remove the `SessionFactory` field or constructor parameter.
2. Add a `TRANSMUTE[manual]` comment in its place:
   ```java
   // TRANSMUTE[manual]: was SessionFactory — inject io.helidon.dbclient.DbClient instead:
   // @Service.Inject
   // private DbClient dbClient;
   ```

## Imports

Remove:
- `io.dropwizard.hibernate.*`
- `io.dropwizard.db.*`
- `org.hibernate.Session`
- `org.hibernate.SessionFactory`
- `org.hibernate.Query`
- `org.hibernate.Criteria`
- Any other `org.hibernate.*` session or query imports

Add (only if `@Service.Singleton` was added to this file):
- `io.helidon.service.registry.Service`

Do NOT add DbClient imports automatically — the developer must configure and inject
`DbClient` after reviewing the manual TODOs.

## DO NOT touch

Do NOT modify anything related to:
- `@Inject`, `@Named`, Guice `Injector` or `Module` — handled by the Injection feature.
- `@Timed`, `@Metered`, `MetricRegistry` — handled by the Metrics Migration recipe.
- JAX-RS HTTP method annotations — handled by the REST Resource recipe.
