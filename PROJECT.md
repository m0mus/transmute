# Transmute — Architecture

## 1. Overview

Transmute is a domain-agnostic Java code migration framework. It orchestrates a
deterministic-first, AI-assisted pipeline that migrates Java projects from one API or
framework to another. Migration knowledge lives entirely in migration modules; the core
framework contains none.

The same infrastructure supports any Java-to-Java migration. The only thing that changes
between, say, Dropwizard→Helidon and Spring→Quarkus is which migration module is on the
classpath.

---

## 2. Goals

1. Provide a reusable, domain-agnostic migration orchestration pipeline.
2. Make migration authoring easy — a markdown file is the minimum viable migration.
3. Support both deterministic transforms (OpenRewrite, text rewriting) and AI-backed
   transforms in a single unified model.
4. Make the migration plan visible and auditable before execution (human-in-the-loop).
5. Recover from compile errors with an AI agent; recover from test failures likewise.
6. Keep migration modules as pure resource JARs when possible — no Java code required.

### Non-Goals

- Migration-specific logic of any kind in the framework itself.
- A centralized server, database, or cloud service — the tool is self-contained.
- Replacing developer validation or test coverage.

---

## 3. Module Structure

```
Transmute/
  transmute-core/         All Java infrastructure:
                            inventory, migration API, planner, workflow,
                            AI agents, CLI, tool implementations
  transmute-dw-helidon/   Pure markdown resources — zero Java, zero deps:
                            recipes and features for Dropwizard 3 → Helidon 4 SE
```

`transmute-dw-helidon` depends on nothing and is assembled with `transmute-core` at runtime
by placing both JARs on the classpath. The same pattern applies to any migration module.

---

## 4. Pipeline Architecture

```
TransmuteCli (entry point)
  └── MigrationWorkflow (10-step sequential pipeline)
        │
        ├── [1]  CopyProjectTool       copy source → output dir (original untouched)
        ├── [2]  JavaProjectVisitor    build ProjectInventory (Java types, imports,
        │                              annotations, supertypes, dependencies)
        ├── [3]  MigrationDiscovery    find Java Migration impls via ClassGraph
        │                              + load *.recipe.md / *.feature.md via MarkdownMigrationLoader
        ├── [4]  MigrationPlanner      evaluate triggers; resolve target files;
        │                              topological sort by order() / after();
        │                              derive scope (FILE vs PROJECT)
        ├── [5]  ApprovePlan           human gate (skipped if --auto-approve)
        │
        ├── [6]  ExecuteMigrations
        │         ├── Java migrations → apply(MigrationContext) once
        │         ├── FILE-scoped AI  → one agent call per file
        │         │     (all recipes targeting the same file merged into one prompt)
        │         └── PROJECT-scoped AI → one agent call for the whole output dir
        │
        ├── [7]  ReviewGate            human gate (skipped if --auto-approve)
        │
        ├── [8]  CompileFixLoop        mvn compile → FixCompileErrorsAgent (max 5 iterations)
        ├── [9]  TestFixLoop           mvn test    → FixTestFailuresAgent  (max 5 iterations)
        └── [10] GenerateReport        writes migration-report.json
```

---

## 5. Migration Types

### 5.1 Java Migrations

Implement `io.transmute.migration.Migration`:

```java
public interface Migration {
    MigrationResult apply(MigrationContext ctx) throws Exception;

    default String name()                              { return getClass().getSimpleName(); }
    default int order()                                { return 50; }
    default List<String> after()                       { return List.of(); }
    default boolean isTriggered(ProjectInventory inv)  { return true; }
}
```

- Discovered automatically by ClassGraph — no registration, no annotation needed.
- Called **once** per pipeline run. The migration uses `ctx.inventory()` to iterate
  files itself (suitable for OpenRewrite and other batch transforms).
- Scope is always PROJECT-like (one call, whole project).

### 5.2 Markdown Migrations (Recipes and Features)

Plain markdown files loaded from the classpath at `recipes/*.recipe.md` or
`features/*.feature.md`. The front-matter configures trigger conditions, ordering,
and postchecks. The markdown body becomes the AI system prompt.

**Recipe** (`type: recipe`) — FILE scope. One AI agent call per matching Java file.
All recipes triggered on the same file are merged into a single combined prompt so
the AI applies all transformations atomically.

**Feature** (`type: feature`) — PROJECT scope when using only `files:` triggers;
FILE scope if any trigger uses `imports`/`annotations`/`superTypes`. Scope is derived
automatically and never declared explicitly.

#### Front-matter reference

```yaml
---
name: My Migration        # required, unique name used in after: references
type: recipe              # recipe | feature
order: 20                 # integer, default 50; lower = runs first
after: [Other Migration]  # runs after these named migrations
triggers:
  - imports: [com.example.OldClass]        # file imports any of these
    annotations: [com.example.OldAnnot]    # file uses any of these annotations
    superTypes: [com.example.OldBase]      # file extends/implements any of these
  - files: [pom.xml, build.gradle]         # project root contains any of these files
postchecks:
  forbidImports: [com.example.OldClass]    # fail if any target file still imports these
  requireImports: [com.example.NewClass]   # fail if target file does not import these
---
```

Multiple trigger objects in the list are OR'd — the migration fires if **any** trigger
matches. Fields within one trigger object are AND'd — all conditions must match.

#### Scope derivation

| Trigger types present | Derived scope |
|-----------------------|---------------|
| Any `imports` / `annotations` / `superTypes` | FILE (one call per matching file) |
| Only `files` (or no triggers) | PROJECT (one call for the whole output dir) |

#### Combined prompt for FILE-scoped recipes

When multiple recipes target the same file, `MigrationWorkflow` builds one merged system
prompt:

```
You are an expert Java developer executing a framework migration.
Apply ALL sections below. Each section declares what it owns.
Do not modify anything not covered by a section below.

## Recipe A (owns: @OldAnnotation)
DO NOT touch: @OtherAnnotation (handled by other sections)
<Recipe A body>

## Recipe B (owns: OldBaseClass)
DO NOT touch: @OldAnnotation (handled by other sections)
<Recipe B body>
```

The `owns:` and `DO NOT touch:` lines are derived from the recipe's front-matter
`annotations:` and `superTypes:` trigger conditions, preventing recipes from
overwriting each other's territory.

---

## 6. Trigger System

### Java migrations

Override `isTriggered(ProjectInventory inventory)` to inspect the inventory and return
`true` when the migration should run. Return `true` always to run unconditionally.

### Markdown migrations

`MigrationPlanner.markdownTriggerFires()` evaluates each trigger object:

- **`imports`**: at least one Java file in the inventory imports a matching prefix.
- **`annotations`**: at least one Java file uses a matching annotation.
- **`superTypes`**: at least one Java file extends/implements a matching type.
- **`files`**: the named file exists in `inventory.getRootDir()`.

For FILE-scoped triggers, the planner also builds the list of matching target files
(used by `MigrationWorkflow` to drive the per-file agent loop).

---

## 7. Package Reference (transmute-core)

```
io.transmute.agent/
  TransmuteCli.java           CLI entry point; parses args → TransmuteConfig → MigrationWorkflow
  TransmuteConfig.java        Immutable config record (projectDir, outputDir, model config, flags)
  ModelFactory.java           Creates ChatModel from TransmuteConfig (OpenAI / OCI GenAI / Ollama)
  logging/
    PromptLogListener.java    Writes all AI prompts + responses to logs/ai-prompts.jsonl
  agent/
    FixCompileErrorsAgent.java  AI service (LC4j): fixes compile errors using file I/O tools
    FixTestFailuresAgent.java   AI service (LC4j): fixes test failures using file I/O tools
  workflow/
    MigrationWorkflow.java    10-step sequential pipeline; ANSI-colored console output

io.transmute.migration/
  Migration.java              Core interface (apply, name, order, after, isTriggered)
  MigrationContext.java       Per-invocation context (inventory, projectState, model, workspace, log)
  MigrationResult.java        Result: success flag, message, list of FileChanges
  FileChange.java             Before/after content of one file
  Workspace.java              Path helpers: sourceDir, outputDir, dryRun flag
  MigrationScope.java         Enum: FILE | PROJECT
  AiMigration.java            Loaded markdown migration (implements Migration + AiMigrationMetadata)
  AiMigrationMetadata.java    Interface: skillName, skillScope, systemPromptSection, ...
  MarkdownTrigger.java        Record: imports, annotations, superTypes, signals, compileErrors, files
  MarkdownPostchecks.java     Record: forbidImports, requireImports
  postcheck/
    PostcheckRunner.java      Evaluates postchecks; returns list of failure messages
    PostcheckRule.java        One rule (type + pattern)
    PostcheckResult.java      Pass/fail with description

io.transmute.catalog/
  MigrationDiscovery.java     Discovers Java Migration impls (ClassGraph) + markdown files
  MigrationPlanner.java       Evaluates triggers; builds sorted, scoped MigrationPlan
  MigrationPlan.java          Ordered list of MigrationExecutionEntry (migration + targetFiles + confidence)
  MarkdownMigrationLoader.java Scans classpath for *.recipe.md / *.feature.md; parses front-matter
  RecipeFrontMatter.java      Jackson-mapped front-matter record
  MigrationConfidence.java    Enum: HIGH | MEDIUM | LOW
  FeatureConflictException.java Thrown when two migrations have an unresolvable ordering conflict
  MigrationLog.java           Append-only log of migration outcomes per file
  MigrationLogEntry.java      One log entry (migration name, file, status, message)
  ProjectState.java           Mutable inter-migration shared state bag

io.transmute.inventory/
  ProjectInventory.java       Collected project metadata (Java files, root dir, warnings, errors)
  JavaFileInfo.java           Per-file metadata (path, imports, annotations, supertypes)
  JavaProjectVisitor.java     OpenRewrite visitor that populates ProjectInventory

io.transmute.tool/
  FileOperationsTool.java     @Tool: read, write, list files (used by AI agents)
  CompileProjectTool.java     @Tool: mvn compile; returns success + error text
  RunTestsTool.java           @Tool: mvn test; returns success + output text
  CopyProjectTool.java        Copies source dir to output dir
  MigrationTools.java         Assembles tool lists for agent builders
```

---

## 8. AI Integration

Transmute uses [LangChain4j](https://github.com/langchain4j/langchain4j) for all AI
interactions. Three providers are supported:

| Provider | Flag | Notes |
|----------|------|-------|
| OpenAI-compatible | `--model-provider openai` | Requires `--api-key`; use `--base-url` for local endpoints |
| OCI Generative AI | `--model-provider oci-genai` | Uses OCI instance principal / API key auth |
| Ollama | `--model-provider ollama` | Requires Ollama running locally |

### AI usage in the pipeline

| Step | AI? | Details |
|------|-----|---------|
| 1–5 | No | Pure Java |
| 6 — Java migrations | No | Deterministic |
| 6 — Recipe/Feature execution | **Yes** | `SingleFileAgent` (FILE) or project-scope agent |
| 7 | No | Human gate |
| 8 — Compile fix | **Yes** | `FixCompileErrorsAgent` with file I/O tools |
| 9 — Test fix | **Yes** | `FixTestFailuresAgent` with file I/O tools |
| 10 | No | JSON report |

### Prompt logging

Set `TRANSMUTE_LOG_PROMPTS=true` (default). All prompts and responses are written to
`logs/ai-prompts.jsonl` as newline-delimited JSON via `PromptLogListener`.

---

## 9. Ordering and Conflict Resolution

Migrations are topologically sorted by two mechanisms:

1. **`order()`** — numeric priority. Lower values run first. Default: 50.
2. **`after()`** — explicit name-based "must run after" constraints.

Markdown migrations use `order:` and `after:` in front-matter. Java migrations override
the interface default methods. Names are compared by exact string equality; markdown
names come from the `name:` field, Java migration names from `getClass().getSimpleName()`
(overridable via `name()`).

If a cycle is detected or an `after:` references a name that is not in the plan,
`MigrationPlanner` throws `FeatureConflictException` unless `--allow-order-conflicts`
is set (in which case it logs a warning and proceeds).

---

## 10. Postcheck Rules

After each FILE-scoped AI migration completes, `PostcheckRunner` evaluates the postchecks
declared in the front-matter against the file's after-content:

| Rule type | Description |
|-----------|-------------|
| `forbidImports` | Fails if the output file still imports any listed prefix |
| `requireImports` | Fails if the output file does not import any listed prefix |

Failures are logged to the console as warnings (yellow `⚠`). They do not abort the
pipeline — they signal that the AI may not have fully applied the migration and flag
the file for manual review.

---

## 11. Adding a New Migration Module

1. Create a Maven project. Add no dependencies (not even `transmute-core` — migrations
   run in the context of the tool's classpath, not compiled against it at test time).
2. Create `src/main/resources/recipes/` and/or `src/main/resources/features/`.
3. Write `.recipe.md` or `.feature.md` files with front-matter + AI prompt body.
4. Optionally implement `Migration` in Java for deterministic transforms.
5. Build the JAR and place it on the classpath alongside `transmute-core-*.jar`:

```bash
java -cp transmute-core-1.0-SNAPSHOT.jar:my-migration-1.0.jar \
     io.transmute.agent.TransmuteCli \
     --project-dir /path/to/project \
     --model-provider openai \
     --api-key $OPENAI_API_KEY
```

No `--skills-package` flag or registration is needed. `MigrationDiscovery` finds
everything automatically.
