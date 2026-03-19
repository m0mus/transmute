# Converter Authoring Guide

This guide covers everything needed to build a Transmute migration module —
a converter like `transmute-dw-helidon`. No Java code required.

---

## 1. Module Structure

A converter module is a plain Maven project with **zero production dependencies**
and **zero Java code**. Everything lives under `src/main/resources/`:

```
my-converter/
  src/main/resources/
    recipes/
      build-migration.recipe.md         ← file-level build migration
      application-bootstrap.recipe.md   ← rewrites main entry point
      jaxrs-rest-resource.recipe.md     ← per-resource migrations
      ...
    features/
      bean-validation.feature.md        ← cross-cutting, composes with recipes
      security-auth.feature.md
      ...
    hints/
      compile-hints.md                  ← injected into compile-fix agent
      test-hints.md                     ← injected into test-fix agent
    catalog/
      dependency-catalog.yml            ← dependency status declarations
```

The framework scans the classpath automatically. No registration needed.

### Minimal `pom.xml`

```xml
<project>
  <parent>
    <groupId>io.transmute</groupId>
    <artifactId>transmute-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
  </parent>
  <artifactId>my-converter</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <!-- test scope only — for recipe integration tests -->
    <dependency>
      <groupId>io.transmute</groupId>
      <artifactId>transmute-core</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

---

## 2. Recipes vs Features

| | Recipe | Feature |
|---|--------|---------|
| **Purpose** | Owns a complete migration for one class type | Handles a cross-cutting concern that co-exists with a recipe |
| **File matching** | A file matches **at most one** recipe | A file can match **any number** of features |
| **Execution** | Applied alone, or merged with features in one AI call | Always merged into a recipe's prompt — never runs standalone |
| **Conflict detection** | No (recipes are exclusive by type) | Yes — two features may not claim the same FQN in `transforms` |
| **`transforms`** | Optional but recommended | Required when two features might target the same file |

**Rule of thumb:**
- Use a **recipe** when you are replacing a framework construct wholesale
  (a JAX-RS resource becomes a Helidon endpoint; a Dropwizard Application becomes a Helidon main class).
- Use a **feature** when you are migrating an annotation or import pattern that
  can appear in files of many different types
  (bean validation annotations, metrics annotations, DI annotations).

---

## 3. File Naming and Location

| Kind | Directory | Extension |
|------|-----------|-----------|
| Recipe | `src/main/resources/recipes/` | `.recipe.md` |
| Feature | `src/main/resources/features/` | `.feature.md` |

The filename is used only for display in error messages. The `name:` field in the
front-matter is the canonical identifier used everywhere else.

---

## 4. File Format

Every file is a standard Markdown file with a YAML front-matter block at the top:

```
---
<YAML front-matter>
---

<Markdown body — becomes the AI system prompt>
```

The body is passed verbatim as the AI's system prompt. Write it as detailed,
structured instructions — examples, tables, and code blocks work well.

---

## 5. Front-Matter Reference

### Required fields

```yaml
name: REST Resource         # Unique display name; used in logs, plan view, journal
type: recipe                # recipe | feature
```

### Optional fields

```yaml
order: 5                    # Integer, default 50. Lower = runs first.
                            # Determines execution order relative to other migrations.

triggers:                   # When to fire this migration (see section 6)
  - ...

transforms:                 # FQN ownership — required for features (see section 7)
  annotations: [...]
  types: [...]

postchecks:                 # Post-execution quality checks (see section 8)
  forbidImports: [...]
  requireImports: [...]
  forbidPatterns: [...]
```

### Ordering guidelines

| Order range | Use for |
|-------------|---------|
| 1–5 | Build file migrations (must run before Java files are touched) |
| 6–20 | Core framework class rewrites (Application, resources, config) |
| 20–40 | Secondary class migrations (health checks, lifecycle, clients) |
| 40–50 | Features and cross-cutting concerns (validation, metrics, DI) |

---

## 6. Triggers

Triggers declare when a migration fires. Multiple trigger objects are **OR'd** —
the migration fires if **any** trigger matches. Fields within one trigger object
are **AND'd** — all non-empty conditions must hold.

```yaml
triggers:
  - annotations: [javax.ws.rs.Path]          # file uses any of these annotations
  - imports: [javax.ws.rs.]                  # file imports any of these prefixes (prefix match)
  - superTypes: [io.dropwizard.Application]  # file extends/implements any of these FQNs
  - files: [pom.xml, build.gradle]           # any of these files exist in the project root
  - signals: [has_jpa_entities]              # all signals present in the project inventory
  - compileErrors: ["cannot find symbol.*MyOldClass"]  # regex matches a compile error
```

### Trigger semantics

| Trigger | Type | Match rule |
|---------|------|------------|
| `imports` | FILE-scoped | Prefix match — `javax.ws.rs.` matches `import javax.ws.rs.GET` |
| `annotations` | FILE-scoped | Exact FQN match on used annotations |
| `superTypes` | FILE-scoped | Exact FQN match on `extends` / `implements` types (full supertype chain) |
| `files` | FILE-scoped | File name exists at project root |
| `signals` | PROJECT-scoped | Named signal present in inventory |
| `compileErrors` | PROJECT-scoped | Regex matches any compile error string |

### Scope derivation

Scope is inferred automatically from trigger types — you never declare it explicitly:

| Trigger types present | Derived scope |
|-----------------------|---------------|
| Any `imports`, `annotations`, `superTypes`, or `files` | **FILE** — one AI call per matching file |
| Only `signals` or `compileErrors`, or no triggers | **PROJECT** — one AI call for the whole output directory |

FILE-scoped migrations are far more precise — prefer them. Only use PROJECT scope
for whole-project structural changes (e.g. adding a config file that doesn't exist yet).

### Combining conditions

```yaml
triggers:
  # Fires on any file that imports javax.ws.rs AND uses @Path
  - imports: [javax.ws.rs.]
    annotations: [javax.ws.rs.Path]

  # OR fires on any file that imports the Dropwizard caching package alone
  - imports: [io.dropwizard.jersey.caching]
```

---

## 7. Transforms (Feature Conflict Detection)

`transforms` declares FQN ownership. It is used in two ways:

1. **Conflict detection** — at load time, the framework checks that no two features
   claim the same FQN. If they do, startup fails with a clear error.

2. **Combined prompt coordination** — when a recipe and one or more features are
   merged into a single AI call for the same file, each migration's `transforms`
   drives the `owns:` / `DO NOT touch:` directives in the combined prompt, preventing
   migrations from overwriting each other's work.

```yaml
transforms:
  annotations:
    - io.dropwizard.auth.Auth          # this feature owns @Auth migration
    - io.dropwizard.auth.Authenticated
  types:
    - io.dropwizard.auth.Authenticator # this feature owns Authenticator subtype migration
```

**Recipes** are implicitly exclusive (a file matches at most one), so conflicts
cannot arise. However, declaring `transforms` in a recipe still produces cleaner
`DO NOT touch:` coordination when features co-execute with it — recommended for
recipes that touch annotations or supertypes also handled by features.

---

## 8. Postchecks

Postchecks are quality assertions evaluated after the migration runs.
Failures are reported as warnings (`[!]`) in the console — they do not abort the pipeline.

```yaml
postchecks:
  forbidImports:
    - javax.ws.rs.              # warn if this import prefix is still present
    - jakarta.ws.rs.

  requireImports:
    - io.helidon.http.Http      # warn if this import is missing after migration

  forbidPatterns:
    - "ResourceExtension"       # regex — warn if this pattern appears in the output
    - "@Path\\("                # still has JAX-RS @Path
```

| Rule | Trigger | Use for |
|------|---------|---------|
| `forbidImports` | Prefix match on import statements | Verifying source-framework imports were removed |
| `requireImports` | Exact match on import statements | Verifying target-framework imports were added |
| `forbidPatterns` | Regex on full file content | Detecting residual patterns that should not appear |

Postchecks are per-recipe/feature. Each migration declares the checks relevant to
its own transformations only.

---

## 9. Body — The AI System Prompt

The markdown body (everything after the second `---`) is the AI's system prompt.
Write it as precise, structured instructions. The AI receives:

```
<your body here>

## Project Context
<AI-generated project summary injected automatically>
```

### Writing effective bodies

**Be explicit about before/after.** Tables and code blocks are the most reliable format:

```markdown
## HTTP method annotations

| Remove | Add |
|--------|-----|
| `@GET` | `@Http.GET` |
| `@POST` + `@Path("/x")` | `@Http.POST` + `@Http.Path("/x")` |
```

**Give compilability instructions explicitly.** State what must compile and what must not remain:

```markdown
The output file MUST compile. For any construct that cannot be automatically
converted, comment it out using a `// TRANSMUTE[unsupported]:` line comment
rather than leaving a compile error.
```

**List explicit imports** — the AI will not guess them correctly without help:

```markdown
## Imports cleanup

Remove: `javax.ws.rs.*`, `io.dropwizard.jersey.params.*`
Add: `io.helidon.http.Http`, `io.helidon.webserver.http.RestServer`
```

**Add DO NOT touch sections** for things handled by other recipes or features:

```markdown
## DO NOT touch

- `@Inject`, `@Named` — handled by the Injection feature
- `@Timed`, `@Metered` — handled by the Metrics feature
```

---

## 10. The `TRANSMUTE` Comment Convention

When a construct cannot be automatically converted, the AI must comment it out
rather than leave a compile error or silently delete functionality.

Use this standard marker format in your recipe body instructions:

```
// TRANSMUTE[<category>]: <description>
```

### Categories

| Category | Meaning | Example use |
|----------|---------|-------------|
| `manual` | Human action required before this compiles or works correctly | Removed `@Auth` parameter — wiring needed |
| `unsupported` | No equivalent exists in the target framework | View classes, task classes |
| `recheck` | Temporarily disabled; re-enable when upstream issue is fixed | Validation disabled due to Helidon bug |

### Examples

```java
// TRANSMUTE[manual]: @Auth User parameter removed — inject via Helidon Security
@Http.GET
@Http.Path("/secure")
public String secureEndpoint() { ... }

// TRANSMUTE[unsupported]: Dropwizard Task has no Helidon equivalent — implement manually
public class MyTask /* extends Task */ {
    // TRANSMUTE[unsupported]: execute() body disabled — see Task migration docs
    /*
    public void execute(ImmutableMultimap<String, String> params, PrintWriter output) {
        ...
    }
    */
}

// TRANSMUTE[recheck]: @NotNull removed — Helidon Validation bug with JPA entities
// @NotNull
private String name;
```

### Block comment preservation

When commenting out a full method or class body (not just an annotation), use
`/* ... */` block comments and place a `TRANSMUTE[unsupported]` marker above:

```java
// TRANSMUTE[unsupported]: ExceptionMapper has no direct Helidon equivalent
public class MyExceptionMapper /* implements ExceptionMapper<IllegalArgumentException> */ {
    /*
    @Override
    public Response toResponse(IllegalArgumentException e) {
        return Response.status(400).entity(e.getMessage()).build();
    }
    */
}
```

---

## 11. Hints

Hints are injected verbatim into the compile-fix and test-fix agents after the
migration journal. They provide domain-specific guidance that applies globally
across all compile/test failures, not just for specific files.

### `hints/compile-hints.md`

Guidance for `FixCompileErrorsAgent`. Use for:
- "Never restore `io.dropwizard.*` imports"
- How to stub classes that have no target equivalent
- Known API name differences that the model often gets wrong

```markdown
## MyFramework → TargetFramework Compile-Fix Guidance

- **NEVER add `com.example.oldframework.*` imports.** If a symbol is missing,
  stub or comment it out. Do NOT restore old dependencies.
- **`OldViewClass`** has no equivalent. Replace with a plain class and add
  `// TRANSMUTE[unsupported]: View not migrated`.
- **`OldTaskClass`** — stub as a plain class with a TODO comment.
```

### `hints/test-hints.md`

Guidance for `FixTestFailuresAgent`. Use for:
- How resource tests should be written (unit tests, not HTTP integration tests)
- Mock reset patterns
- Assertion style adjustments

```markdown
## MyFramework → TargetFramework Test-Fix Guidance

- **Resource tests must be plain JUnit/Mockito unit tests**, not HTTP integration tests.
- **`OldResourceExtension` has no equivalent** — rewrite as direct unit tests that
  instantiate the resource class and call methods directly.
```

---

## 12. Dependency Catalog

Declare the migration status of the source framework's dependencies so users
see meaningful symbols in the plan view rather than `[=] passthrough`.

### `catalog/dependency-catalog.yml`

```yaml
# Specific artifact — fully handled
- groupId: io.dropwizard
  artifactId: dropwizard-core
  status: replaced
  notes: Application + Configuration handled by recipes

# Specific artifact — partially handled
- groupId: io.dropwizard
  artifactId: dropwizard-hibernate
  status: partial
  notes: AbstractDAO + @UnitOfWork → TODOs; DbClient wiring manual

# Wildcard — any unlisted artifact in this groupId
- groupId: io.dropwizard
  artifactId: "*"
  status: unsupported
  notes: No specific recipe; verify removal or find a target-framework alternative
```

### Status values

| Status | Symbol | Meaning |
|--------|--------|---------|
| `replaced` | `[+]` | Full recipe coverage — migrated automatically |
| `partial` | `[~]` | Partial automation; manual steps remain |
| `unsupported` | `[!]` | No automation; must be handled manually |
| `passthrough` | `[=]` | Not a source-framework dependency (default for unlisted) |
| `unknown` | `[?]` | Explicit unknown — use sparingly |

### Wildcard entries

A `artifactId: "*"` entry matches any artifact in that `groupId` not covered by
a more specific entry. Use wildcards to classify entire groupIds as a fallback:

```yaml
- groupId: io.dropwizard
  artifactId: "*"
  status: unsupported
  notes: No specific recipe; verify removal or find a Helidon alternative

- groupId: com.codahale.metrics
  artifactId: "*"
  status: replaced
  notes: All Codahale metrics replaced by Micrometer
```

Lookup order: exact `groupId:artifactId` first, then `groupId:*` wildcard.

---

## 13. Where to Start

Build recipes and catalog entries in this order:

### Step 1 — Dependency catalog

Write the catalog first. It takes 15 minutes and immediately gives the user
a meaningful plan view showing which dependencies are handled vs. not.
Start with specific entries for the artifacts you know your converter covers,
then add wildcard entries for the rest of the source groupId.

### Step 2 — Build file migration (order 1)

This is always the first recipe. Without it the project won't compile at all.
It rewrites `pom.xml` / `build.gradle` to use the target framework's BOM and
removes all source-framework dependencies.

### Step 3 — Application entry point (order 2)

Rewrites the main class. This is usually a complete rewrite with a fixed pattern —
straightforward to write and has high impact since every project has exactly one.

### Step 4 — Core resource/controller type (order 5–10)

The most-used file type in the source framework. For REST frameworks this is
usually the resource/controller annotation pattern. This recipe will run on
the most files, so get it right before moving on.

### Step 5 — Remaining recipes (order 10–40)

Health checks, lifecycle, clients, exception mappers — each covering one
class archetype from the source framework.

### Step 6 — Features (order 50)

Cross-cutting concerns — DI, validation, metrics, security. Write these after
the recipes because you need to know what recipes cover before you can define
`DO NOT touch:` boundaries for features.

### Step 7 — Hints

Write compile-hints.md and test-hints.md after you've run the converter on a
real project at least once and observed what the fix agents get wrong.
Hints are the most effective investment after the first real-world test run.

---

## 14. Checklist for a New Migration File

Before committing a recipe or feature:

- [ ] `name:` is unique across all recipes and features
- [ ] `type:` is `recipe` or `feature`
- [ ] At least one trigger defined (or explicitly intending PROJECT scope)
- [ ] `transforms:` declared if this is a feature (required for conflict detection)
- [ ] `postchecks.forbidImports` lists the source-framework import prefixes this migration removes
- [ ] Body includes explicit import lists (what to remove, what to add)
- [ ] Body includes `TRANSMUTE[<category>]` instructions for anything that cannot be automated
- [ ] Body includes a `## DO NOT touch` section for FQNs owned by other migrations
- [ ] Body states that the output file must compile (or explicitly stubs what cannot)
- [ ] Dependency catalog updated if new source-framework artifacts are covered
