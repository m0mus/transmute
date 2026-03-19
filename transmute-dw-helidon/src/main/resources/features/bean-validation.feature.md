---
name: Bean Validation Migration
type: feature
triggers:
  - imports: [javax.validation]
  - imports: [jakarta.validation]
  - imports: [org.hibernate.validator.constraints]
  - imports: [io.dropwizard.validation]
order: 16
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

# TEMPORARY: Helidon Validation disabled due to annotation processor bug

Helidon's `@Validation.Validated` annotation processor (`ValidatedTypeGenerator`) generates
uncompilable `*__Validated.java` files when applied to JPA entity classes. The generated
switch statements have unreachable statements because no validation cases are emitted for
field-level constraints. Bug filed with Helidon team.

**This feature is a temporary stub.** The full migration to `io.helidon.validation.Validation`
is preserved in `bean-validation.feature.md.disabled` and will be re-enabled once the
Helidon bug is fixed.

## What to do (temporary)

Comment out all validation annotations and remove validation imports, leaving the code
compilable. Add a `TRANSMUTE[manual]` comment above each affected class.

### Step 1 — Add a class-level TODO

```java
// TRANSMUTE[manual]: Bean validation annotations removed — Helidon Validation
//   annotation processor has a known bug with JPA entities (unreachable statement in
//   generated *__Validated.java). Re-enable validation once the bug is fixed.
//   See: bean-validation.feature.md.disabled for the full migration instructions.
```

### Step 2 — Comment out all validation annotations

Comment out every constraint annotation on fields, method parameters, and return types:
- `@NotNull`, `@NotEmpty`, `@NotBlank`
- `@Min`, `@Max`, `@Size`, `@Pattern`, `@Email`
- `@Valid`, `@Validated`, `@Positive`, `@Negative`
- `@AssertTrue`, `@AssertFalse`
- Any other `javax.validation.*`, `jakarta.validation.*`, `org.hibernate.validator.*`,
  or `io.dropwizard.validation.*` annotation

```java
// Before
@NotNull
private String name;

// After
// @NotNull  // TRANSMUTE[recheck]: re-enable once Helidon Validation bug is fixed
private String name;
```

### Step 3 — Remove all validation imports

Remove all imports from:
- `javax.validation.*`
- `jakarta.validation.*`
- `org.hibernate.validator.constraints.*`
- `io.dropwizard.validation.*`

Do NOT add any replacement imports.

### Step 4 — Remove `helidon-validation` from pom.xml

Do NOT add `helidon-validation` to `pom.xml`. It is not needed when validation is disabled.
