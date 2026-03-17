---
name: Dropwizard Configuration
type: recipe
triggers:
  - superTypes: [io.dropwizard.core.Configuration]
  - superTypes: [io.dropwizard.Configuration]
order: 3
after: [Build File Migration]
postchecks:
  forbidImports:
    - io.dropwizard.core.Configuration
    - io.dropwizard.Configuration
---

This file extends the Dropwizard `Configuration` class and must be migrated to a plain
config-accessor class that reads from the Helidon 4 SE `Config` tree.

## Class declaration

Remove `extends Configuration` (and any generic variant). Rename the class from e.g.
`MyConfig` to `AppConfig` only if the original name is generic; otherwise keep the original name.

Add a constructor and a static factory method:

```java
// Before
public class MyConfig extends Configuration {
    @JsonProperty private String host = "localhost";
    @NotNull @Valid private DatabaseFactory database = new DatabaseFactory();
    public String getHost() { return host; }
}
```

```java
// After
public class MyConfig {
    private final Config config;

    public MyConfig(Config config) { this.config = config; }

    public static MyConfig create() { return new MyConfig(Config.global()); }

    public String host() { return config.get("host").asString().orElse("localhost"); }

    // TODO: DatabaseFactory → configure DataSource via config.get("database")
    // See: https://helidon.io/docs/v4/se/dbclient
}
```

## Field-to-accessor conversion

For each field in the original class:

1. **Simple scalar fields** (`String`, `int`, `boolean`, `long`, `double`, etc.):
   - Remove the field declaration.
   - Remove the getter method.
   - Add an accessor method that reads from `config.get("<fieldName>")`:
     - `String` → `.asString().orElse("<defaultValue>")`
     - `int` / `Integer` → `.asInt().orElse(<defaultValue>)`
     - `boolean` / `Boolean` → `.asBoolean().orElse(<defaultValue>)`
     - `long` / `Long` → `.asLong().orElse(<defaultValue>)`
     - `double` / `Double` → `.asDouble().orElse(<defaultValue>)`
   - Use the original field name as the config key (keep camelCase — Helidon config maps
     kebab-case YAML keys to camelCase accessors automatically).
   - If the field had no default value (was `null`), use `.orElse(null)`.

2. **Complex / nested fields** (Dropwizard factory objects like `DatabaseFactory`,
   `HttpClientConfiguration`, etc.):
   - Remove the field declaration and getter.
   - Add a TODO comment as a method stub:
     ```java
     // TODO: <OriginalType> → configure via config.get("<fieldName>")
     // See Helidon docs for the equivalent component
     ```

3. **Collection fields** (`List<T>`, `Set<T>`, `Map<K,V>`):
   - Remove the field declaration and getter.
   - Add an accessor method using:
     - `config.get("<fieldName>").asList(<T>.class).orElse(List.of())`
     - or add a `// TODO: map collection` comment for complex generic types.

## Annotation removal

Remove the following annotations from fields and methods without replacement:
- `@JsonProperty`
- `@JsonIgnore`
- `@JsonAlias`
- Any other Jackson annotation (`com.fasterxml.jackson.annotation.*`)

Replace the following annotations with a TODO comment on the same line:
- `@NotNull` → `// TODO: validate after reading`
- `@Valid` → `// TODO: validate after reading`
- `@NotEmpty`, `@Size`, `@Min`, `@Max` → `// TODO: validate after reading`

## Imports

Remove:
- `io.dropwizard.core.Configuration`
- `io.dropwizard.Configuration`
- `com.fasterxml.jackson.annotation.*`
- `javax.validation.*`
- `jakarta.validation.*`
- Any Dropwizard factory imports (`io.dropwizard.db.*`, `io.dropwizard.client.*`, etc.)

Add:
- `io.helidon.config.Config`
