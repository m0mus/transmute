---
name: Bean Validation Migration
type: feature
order: 16
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
owns:
  annotations:
    - javax.validation.Valid
    - javax.validation.constraints.NotNull
    - javax.validation.constraints.NotEmpty
    - javax.validation.constraints.NotBlank
    - javax.validation.constraints.Min
    - javax.validation.constraints.Max
    - javax.validation.constraints.Size
    - javax.validation.constraints.Pattern
    - javax.validation.constraints.Email
    - javax.validation.constraints.Positive
    - javax.validation.constraints.Negative
    - javax.validation.constraints.AssertTrue
    - javax.validation.constraints.AssertFalse
    - jakarta.validation.Valid
    - jakarta.validation.constraints.NotNull
    - jakarta.validation.constraints.NotEmpty
    - jakarta.validation.constraints.NotBlank
    - jakarta.validation.constraints.Min
    - jakarta.validation.constraints.Max
    - jakarta.validation.constraints.Size
    - jakarta.validation.constraints.Pattern
    - jakarta.validation.constraints.Email
    - jakarta.validation.constraints.Positive
    - jakarta.validation.constraints.Negative
    - jakarta.validation.constraints.AssertTrue
    - jakarta.validation.constraints.AssertFalse
    - org.hibernate.validator.constraints.NotEmpty
    - org.hibernate.validator.constraints.NotBlank
    - io.dropwizard.validation.ValidationMethod
---

This file uses Bean Validation annotations (`javax.validation` or `jakarta.validation`).
Helidon 4 SE has its own declarative validation API in `io.helidon.validation.Validation`
with a different annotation structure. Migrate all validation annotations to the Helidon
equivalents.

## Step 1 — Add @Validation.Validated to the class

Add `@Validation.Validated` to every class that has validation annotations on its methods
or parameters. This triggers Helidon's annotation-processor-generated interception.

```java
// Before
public class PersonResource {

// After
@Validation.Validated
public class PersonResource {
```

## Step 2 — Move annotations from private fields to public setters

**Critical:** Helidon's `@Validation.Validated` annotation processor only processes
annotations on **public methods**, not on private fields. Annotations on private fields
cause uncompilable generated code.

If the original code has annotations on private fields, move each annotation to the
corresponding public setter method:

```java
// Before
@NotNull
private String name;

public void setName(String name) { this.name = name; }

// After
private String name;

@Validation.NotNull
public void setName(String name) { this.name = name; }
```

If no public setter exists for an annotated private field, add one:

```java
// Before
@NotNull
private String name;

// After
private String name;

@Validation.NotNull
public void setName(String name) { this.name = name; }
```

Annotations on public method parameters and return types stay in place — only move
annotations that are currently on private fields.

## Step 3 — Replace constraint annotations

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
// TRANSMUTE[manual]: was @XYZ — find Helidon Validation equivalent or write custom constraint
```

## Step 4 — Dropwizard-specific validators

| Remove | Add |
|--------|-----|
| `@io.dropwizard.validation.PortRange` | `@Validation.Integer.Min(1)` + `@Validation.Integer.Max(65535)` |
| `@io.dropwizard.validation.Validated` | `@Validation.Valid` |
| All other `io.dropwizard.validation.*` | Remove + `// TRANSMUTE[manual]: was DW validator — find Helidon Validation equivalent` |

## Step 5 — Update imports

Remove all of:
- `javax.validation.*`
- `jakarta.validation.*`
- `org.hibernate.validator.constraints.*`
- `io.dropwizard.validation.*`

Add a single import:
```java
import io.helidon.validation.Validation;
```

## Step 6 — Note on pom.xml

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
