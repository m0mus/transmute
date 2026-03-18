---
name: Bean Validation Migration
type: feature
triggers:
  - imports: [javax.validation]
  - imports: [org.hibernate.validator.constraints]
  - imports: [io.dropwizard.validation]
postchecks:
  forbidImports:
    - javax.validation
    - io.dropwizard.validation
---

This file uses Bean Validation annotations from the `javax.validation` namespace (Java EE 8)
or Dropwizard-specific validators. Helidon 4 SE uses the Jakarta EE 10 namespace
(`jakarta.validation`). The annotation names are identical — only the import package changes.

## javax.validation → jakarta.validation

Replace every import whose package starts with `javax.validation` with the equivalent
`jakarta.validation` import:

```java
// Before
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Email;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Negative;
import javax.validation.constraints.NegativeOrZero;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.AssertFalse;
import javax.validation.constraints.Future;
import javax.validation.constraints.Past;
import javax.validation.constraints.Digits;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.DecimalMax;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.validation.Validation;
```

```java
// After — identical names, only the package prefix changes
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
// ... etc
```

**Rule:** Replace every `javax.validation.` prefix in import statements with `jakarta.validation.`.
Do not change annotation usage in the code — `@Valid`, `@NotNull`, `@Size(min=1)`, etc. stay unchanged.

## org.hibernate.validator.constraints

Hibernate Validator constraints (`@NotBlank`, `@URL`, `@CreditCardNumber`, `@Length`, etc.)
are fully compatible with Jakarta Validator and do **not** need to be changed.
Leave `org.hibernate.validator.constraints.*` imports as-is.

One exception — `@NotEmpty` was moved to the Jakarta standard:
- If the import is `org.hibernate.validator.constraints.NotEmpty`, replace with
  `jakarta.validation.constraints.NotEmpty`.

## io.dropwizard.validation

Dropwizard ships additional custom validators with no direct Jakarta equivalent.
For each one found, remove the import and add a `DW_MIGRATION_TODO` at the usage site:

| Dropwizard annotation | Action |
|-----------------------|--------|
| `@ValidationMethod` | Remove from method; add: `// DW_MIGRATION_TODO[manual]: was @ValidationMethod — implement ConstraintValidator<A,T>` |
| `@MinDuration` / `@MaxDuration` | Remove annotation; add: `// DW_MIGRATION_TODO[manual]: was @MinDuration/@MaxDuration — validate Duration manually or write custom ConstraintValidator` |
| `@MinSize` / `@MaxSize` | Replace with `@Size(min=N)` / `@Size(max=N)` from `jakarta.validation.constraints` if values are constants; otherwise add TODO |
| `@OneOf` | Remove annotation; add: `// DW_MIGRATION_TODO[manual]: was @OneOf — use @Pattern or write custom ConstraintValidator` |
| `@PortRange` | Replace with `@Min(1) @Max(65535)` from `jakarta.validation.constraints`; remove original |
| `@Validated` | Replace with `jakarta.validation.Valid` |
| Any other `io.dropwizard.validation.*` | Remove import; add: `// DW_MIGRATION_TODO[manual]: was io.dropwizard.validation.X — find Jakarta equivalent or write custom ConstraintValidator` |

## Imports

Remove:
- All `javax.validation.*` imports
- All `io.dropwizard.validation.*` imports

Add:
- `jakarta.validation.*` equivalents for every `javax.validation.*` import that was present
- For `@Min` / `@Max` replacements: `jakarta.validation.constraints.Min` / `jakarta.validation.constraints.Max`

Do NOT change:
- `org.hibernate.validator.constraints.*` (except the `NotEmpty` case above)
- Any annotation usage in the code body — only imports change

## DO NOT touch

Do NOT modify anything related to:
- `@JsonProperty`, `@JsonIgnore`, Jackson annotations — handled by other recipes.
- JAX-RS / `@Http.*` annotations — handled by the REST Resource recipe.
- `@Inject`, `@Singleton` — handled by the Injection Migration feature.
