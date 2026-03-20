---
name: Hibernate / JPA Data Migration
type: recipe
order: 4
triggers:
  - imports: [io.dropwizard.hibernate]
    superTypes: [io.dropwizard.hibernate.AbstractDAO]
  - imports: [javax.persistence]
    annotations: [javax.persistence.Entity]
  - imports: [jakarta.persistence]
    annotations: [jakarta.persistence.Entity]
postchecks:
  forbidImports:
    - io.dropwizard.hibernate
    - javax.persistence
    - org.hibernate.SessionFactory
---

Migrate Dropwizard Hibernate DAO classes and JPA entity classes to Helidon Data.

Helidon Data uses `jakarta.persistence.*` annotations for entities (same as JPA) and
`@Data.Repository` interfaces for data access. Entities keep their JPA annotations but
switch from `javax.persistence` to `jakarta.persistence`. DAO classes are rewritten as
repository interfaces.

## JPA Entity classes (annotated with `@Entity`)

Entity classes keep their JPA annotations. The only change is the import namespace:

| Remove                         | Add                            |
|--------------------------------|--------------------------------|
| `javax.persistence.Entity`     | `jakarta.persistence.Entity`   |
| `javax.persistence.Table`      | `jakarta.persistence.Table`    |
| `javax.persistence.Id`         | `jakarta.persistence.Id`       |
| `javax.persistence.Column`     | `jakarta.persistence.Column`   |
| `javax.persistence.GeneratedValue` | `jakarta.persistence.GeneratedValue` |
| `javax.persistence.GenerationType` | `jakarta.persistence.GenerationType` |
| `javax.persistence.NamedQuery` | `jakarta.persistence.NamedQuery` |
| `javax.persistence.ManyToOne`  | `jakarta.persistence.ManyToOne` |
| `javax.persistence.OneToMany`  | `jakarta.persistence.OneToMany` |
| `javax.persistence.JoinColumn` | `jakarta.persistence.JoinColumn` |
| (any other `javax.persistence.*`) | (same class under `jakarta.persistence.*`) |

Replace **all** `javax.persistence` imports with their `jakarta.persistence` equivalents.
Do NOT change annotations, field types, or class structure — only the import prefix.

Also replace `javax.validation.constraints.*` with `jakarta.validation.constraints.*`
(e.g., `@NotNull`, `@Min`, `@Max`, `@NotEmpty`, `@Valid`).

### Example

```java
// Before
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.Id;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Column;
import javax.persistence.NamedQuery;

@Entity
@Table(name = "people")
@NamedQuery(name = "findAll", query = "SELECT p FROM Person p")
public class Person {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    // ...
}
```

```java
// After — only imports change
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.NamedQuery;

@Entity
@Table(name = "people")
@NamedQuery(name = "findAll", query = "SELECT p FROM Person p")
public class Person {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    // ...
}
```

## DAO classes (extending `AbstractDAO`)

Rewrite Dropwizard Hibernate DAO classes as Helidon Data repository interfaces.

### Step 1 — Determine entity type and ID type

From the DAO's `extends AbstractDAO<EntityType>`, identify:
- The entity type (e.g., `Person`)
- The entity's `@Id` field type (e.g., `Long`, `Integer`, `String`)

### Step 2 — Rewrite as a `@Data.Repository` interface

Replace the class with an interface annotated with `@Data.Repository` that extends
`Data.CrudRepository<EntityType, IdType>`.

### Step 3 — Convert methods to repository query methods

Map DAO methods to Helidon Data repository method-name queries or `@Data.Query` annotations:

| Dropwizard DAO pattern | Helidon Data equivalent |
|------------------------|------------------------|
| `persist(entity)` | inherited from `CrudRepository.insert(entity)` |
| `get(id)` | inherited from `CrudRepository.findById(id)` → returns `Optional<E>` |
| `findAll()` via `namedTypedQuery(...)` | inherited from `CrudRepository.findAll()` — do NOT re-declare; **returns `Stream<E>`**, so callers must collect: `dao.findAll().collect(Collectors.toList())` |
| `findById(id)` | `Optional<E> findById(IdType id)` (inherited) |
| Custom HQL/JPQL query | `@Data.Query("SELECT ...")` on a method |
| `currentSession()` usage | `// TRANSMUTE[manual]: use Data.SessionRepository for direct EntityManager access` |

### Step 4 — Remove Dropwizard/Hibernate imports

Remove:
- `io.dropwizard.hibernate.AbstractDAO`
- `io.dropwizard.hibernate.UnitOfWork`
- `org.hibernate.SessionFactory`
- `org.hibernate.query.Query`

Add:
- `io.helidon.data.Data`

### Example

```java
// Before — Dropwizard DAO
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.SessionFactory;
import java.util.List;
import java.util.Optional;

public class PersonDAO extends AbstractDAO<Person> {
    public PersonDAO(SessionFactory factory) {
        super(factory);
    }

    public Optional<Person> findById(Long id) {
        return Optional.ofNullable(get(id));
    }

    public Person create(Person person) {
        return persist(person);
    }

    public List<Person> findAll() {
        return list(namedTypedQuery("com.example.helloworld.core.Person.findAll"));
    }
}
```

```java
// After — Helidon Data Repository
import io.helidon.data.Data;
import java.util.Optional;

@Data.Repository
public interface PersonDAO extends Data.CrudRepository<Person, Long> {

    // findById(Long) is inherited from CrudRepository

    // insert(Person) replaces persist/create — inherited from CrudRepository

    // findAll() is inherited from CrudRepository — do NOT declare it here
    // Calling code can use dao.findAll() without any extra declaration
}
```

### Step 5 — Add convenience methods that match calling code

If the DAO had a method like `create(entity)` that other files call, add a matching
`default` method to the repository interface that delegates to the CrudRepository method:

```java
default Person create(Person person) {
    return insert(person);
}
```

This prevents downstream compile errors in resource classes that call `dao.create(...)`.
Only add these wrappers when the original DAO had a method with that name.

### Notes

- `@Data.Repository` interfaces are auto-discovered by the Helidon service registry.
  No manual registration is needed.
- Constructor injection of `SessionFactory` is eliminated — the repository is an interface.
- If a resource class was injecting the DAO via constructor, it should now inject the
  repository interface instead (it will be auto-provided by the service registry).
- **`CrudRepository.findAll()` returns `Stream<E>`**, not `List<E>`. In any resource class
  that calls `dao.findAll()` and assigns the result to a `List`, add `.collect(Collectors.toList())`
  and `import java.util.stream.Collectors`. Do this proactively — do not wait for compile errors.
- `@UnitOfWork` annotations on resource methods should be removed. Helidon Data manages
  persistence contexts automatically. Add a `TRANSMUTE[manual]` marker if complex transaction boundaries existed:
  ```java
  // TRANSMUTE[manual]: @UnitOfWork removed — review transaction boundaries
  ```

## DO NOT touch

Do NOT modify anything related to:
- `@Http.GET`, `@Http.POST`, `@RestServer.Endpoint` — handled by the REST Resource recipe.
- `@Service.Singleton`, `@Service.Inject` — handled by the Injection Migration recipe.
