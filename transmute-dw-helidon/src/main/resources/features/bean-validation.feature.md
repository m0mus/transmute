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
Helidon 4 SE declarative validation uses `io.helidon.validation.Validation`.

## Step 1 — Add @Validation.Validated

Add `@Validation.Validated` to **every** class that has validation annotations anywhere
(fields, methods, or parameters). This triggers Helidon's annotation-processor-generated
interception.

```java
@Validation.Validated
public class PersonResource {
```

## Step 2 — Move annotations to public getters

Helidon's `ValidatedTypeGenerator` only processes annotations on **public, no-argument,
non-void methods** (getters) and **non-private fields**. Annotations on private fields or
void setter methods are silently ignored and cause the generated `*__Validated.java` to
have an empty property switch, making the trailing `;` an unreachable statement. This
applies to ALL classes, including JPA `@Entity` classes.

Move every validation annotation from a private field to the corresponding public getter.
If no getter exists, add one.

```java
// Before
@Min(value = 0)
@Max(value = 9999)
private int yearBorn;

public int getYearBorn() { return yearBorn; }
public void setYearBorn(int yearBorn) { this.yearBorn = yearBorn; }

// After — annotation moved to the public getter
private int yearBorn;

@Validation.Integer.Min(0)
@Validation.Integer.Max(9999)
public int getYearBorn() { return yearBorn; }
public void setYearBorn(int yearBorn) { this.yearBorn = yearBorn; }
```

### Record DTOs

For Java records, keep converted validation annotations on the record components.

```java
@Validation.Validated
public record MyDto(@Validation.String.NotBlank String name,
                    @Validation.Integer.Min(0) int age) {
}
```

## Step 3 — Replace constraint annotations

Replace Bean Validation annotations with the Helidon equivalents:

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

## Step 4 — Validated body parameters

If the source had a validated REST body parameter such as:

```java
public Person create(@Valid Person person)
```

convert it to:

```java
public Person create(@Validation.Valid @Http.Entity Person person)
```

## Step 5 — Dropwizard-specific validators

| Remove | Add |
|--------|-----|
| `@io.dropwizard.validation.PortRange` | `@Validation.Integer.Min(1)` + `@Validation.Integer.Max(65535)` |
| `@io.dropwizard.validation.Validated` | `@Validation.Valid` |
| other `io.dropwizard.validation.*` | remove + `TRANSMUTE[manual]` comment |

## Step 6 — Imports

Remove:
- `javax.validation.*`
- `jakarta.validation.*`
- `org.hibernate.validator.constraints.*`
- `io.dropwizard.validation.*`

Add:

```java
import io.helidon.validation.Validation;
```

## Step 7 — Build note

The build must include:

```xml
<dependency>
    <groupId>io.helidon.validation</groupId>
    <artifactId>helidon-validation</artifactId>
</dependency>
```

The Build File Migration recipe handles this; do not modify `pom.xml` here.
