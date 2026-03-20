## Dropwizard → Helidon Compile-Fix Guidance

- **Dropwizard task classes** (`PostBodyTask`, `Task`, `TemplateTask`) have no Helidon equivalent.
  Stub them as plain classes with a `// TODO: migrate Dropwizard task` comment. Do NOT try to import or extend these classes.
- **Dropwizard view classes** (`View`) have no Helidon equivalent.
  Stub them as plain classes with a `// TODO: migrate Dropwizard view` comment.
- **`io.dropwizard.jersey.caching.CacheControl`** — remove the annotation and add a `// TODO: implement caching` comment.
- **`io.dropwizard.views.common.*`** — remove the import and stub the class body.
- **NEVER add `io.dropwizard.*` imports.** If a symbol is missing from a Dropwizard package,
  stub or remove it. Do NOT restore Dropwizard dependencies.
- **`io.helidon.validation.Validation` unresolved** — do NOT revert to `javax.validation` or
  `jakarta.validation`. Add `helidon-validation` to `pom.xml` dependencies instead:
  ```xml
  <dependency>
      <groupId>io.helidon.validation</groupId>
      <artifactId>helidon-validation</artifactId>
  </dependency>
  ```
- **`*__Validated.java: unreachable statement`** — A validation annotation is on a private
  field or a void setter. Helidon's `ValidatedTypeGenerator` only reads annotations from
  **public, no-argument, non-void methods** (getters) and non-private fields. Move the
  annotation from the private field or setter to the corresponding **public getter** in the
  source file. Do NOT remove `@Validation.Validated` or the constraint annotation.
