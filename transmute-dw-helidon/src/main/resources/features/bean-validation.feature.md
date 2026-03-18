---
name: Bean Validation Migration
type: feature
triggers:
  - imports: [javax.validation]
  - imports: [jakarta.validation]
  - imports: [org.hibernate.validator.constraints]
  - imports: [io.dropwizard.validation]
postchecks:
  forbidImports:
    - javax.validation
    - jakarta.validation
    - org.hibernate.validator.constraints
    - io.dropwizard.validation
---

This file uses Bean Validation annotations (`javax.validation` or `jakarta.validation`).
Helidon 4 SE has its own declarative validation API in `io.helidon.validation.Validation`
with a different annotation structure. Migrate all validation annotations to the Helidon
equivalents.

## Step 1 — Add @Validation.Validated to the class

Add `@Validation.Validated` to every class that has validation annotations on its fields,
methods, or parameters. This triggers Helidon's annotation-processor-generated interception.

```java
// Before
public class PersonResource {

// After
@Validation.Validated
public class PersonResource {
```

## Step 2 — Replace constraint annotations

Replace every Bean Validation annotation with the Helidon equivalent:

| Remove | Add |
|--------|-----|
| `@NotNull` | `@Validation.NotNull` |
| `@Null` | `@Validation.Null` |
| `@Valid` | `@Validation.Valid` |
| `@NotEmpty` (String) | `@Validation.String.NotEmpty` |
| `@NotBlank` | `@Validation.String.NotBlank` |
| `@Size(min=N, max=M)` on String | `@Validation.String.Length(min=N, max=M)` |
| `@Size(min=N, max=M)` on Collection/Map | `@Validation.Collection.Size(min=N, max=M)` |
| `@Pattern(regexp="...")` | `@Validation.String.Pattern(pattern="...")` |
| `@Email` | `@Validation.Email` |
| `@Min(N)` on int/Integer | `@Validation.Integer.Min(N)` |
| `@Max(N)` on int/Integer | `@Validation.Integer.Max(N)` |
| `@Min(N)` on long/Long | `@Validation.Long.Min(N)` |
| `@Max(N)` on long/Long | `@Validation.Long.Max(N)` |
| `@Min(N)` on other number | `@Validation.Number.Min(N)` |
| `@Max(N)` on other number | `@Validation.Number.Max(N)` |
| `@Positive` | `@Validation.Number.Positive` |
| `@PositiveOrZero` | `@Validation.Number.PositiveOrZero` |
| `@Negative` | `@Validation.Number.Negative` |
| `@NegativeOrZero` | `@Validation.Number.NegativeOrZero` |
| `@Digits(integer=N, fraction=M)` | `@Validation.Number.Digits(integer=N, fraction=M)` |
| `@DecimalMin` / `@DecimalMax` | `@Validation.Number.Min` / `@Validation.Number.Max` |
| `@AssertTrue` | `@Validation.Boolean.True` |
| `@AssertFalse` | `@Validation.Boolean.False` |
| `@Future` on Calendar | `@Validation.Calendar.Future` |
| `@FutureOrPresent` on Calendar | `@Validation.Calendar.FutureOrPresent` |
| `@Past` on Calendar | `@Validation.Calendar.Past` |
| `@PastOrPresent` on Calendar | `@Validation.Calendar.PastOrPresent` |

For any annotation not listed above, remove it and add:
```java
// DW_MIGRATION_TODO[manual]: was @XYZ — find Helidon Validation equivalent or write custom constraint
```

## Step 3 — Dropwizard-specific validators

| Remove | Add |
|--------|-----|
| `@io.dropwizard.validation.PortRange` | `@Validation.Integer.Min(1)` + `@Validation.Integer.Max(65535)` |
| `@io.dropwizard.validation.Validated` | `@Validation.Valid` |
| All other `io.dropwizard.validation.*` | Remove + `// DW_MIGRATION_TODO[manual]: was DW validator — find Helidon Validation equivalent` |

## Step 4 — Update imports

Remove all of:
- `javax.validation.*`
- `jakarta.validation.*`
- `org.hibernate.validator.constraints.*`
- `io.dropwizard.validation.*`

Add a single import:
```java
import io.helidon.validation.Validation;
```

## Step 5 — Note on pom.xml

The `helidon-validation` module must be on the classpath. The Build File Migration recipe
adds it as part of the Helidon BOM. If it is missing, add:
```xml
<dependency>
    <groupId>io.helidon.validation</groupId>
    <artifactId>helidon-validation</artifactId>
</dependency>
```
Do NOT modify `pom.xml` in this step — that is handled by the Build File Migration recipe.

## DO NOT touch

Do NOT modify anything related to:
- JAX-RS / `@Http.*` annotations — handled by the REST Resource recipe.
- `@Inject`, `@Singleton` — handled by the Injection Migration feature.
- `@JsonProperty`, `@JsonIgnore` — handled by other recipes.
