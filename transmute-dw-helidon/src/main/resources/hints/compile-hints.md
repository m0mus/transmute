## Dropwizard → Helidon Compile-Fix Guidance

- **Dropwizard task classes** (`PostBodyTask`, `Task`, `TemplateTask`) have no Helidon equivalent.
  Stub them as plain classes with a `// TODO: migrate Dropwizard task` comment. Do NOT try to import or extend these classes.
- **Dropwizard view classes** (`View`) have no Helidon equivalent.
  Stub them as plain classes with a `// TODO: migrate Dropwizard view` comment.
- **`io.dropwizard.jersey.caching.CacheControl`** — remove the annotation and add a `// TODO: implement caching` comment.
- **`io.dropwizard.views.common.*`** — remove the import and stub the class body.
- **NEVER add `io.dropwizard.*` imports.** If a symbol is missing from a Dropwizard package,
  stub or remove it. Do NOT restore Dropwizard dependencies.
