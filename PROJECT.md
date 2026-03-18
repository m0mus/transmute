# Transmute — Architecture

## 1. Overview

Transmute is a domain-agnostic Java code migration framework. It orchestrates an
AI-driven pipeline that migrates Java projects from one API or framework to another.
Migration knowledge lives entirely in migration modules; the core framework contains none.

The only thing that changes between, say, Dropwizard→Helidon and Spring→Quarkus is which
migration module is on the classpath.

---

## 2. Goals

1. Provide a reusable, domain-agnostic migration orchestration pipeline.
2. Make migration authoring easy — a markdown file is the minimum viable migration.
3. Make the migration plan visible and auditable before execution (human-in-the-loop).
4. Recover from compile errors with an AI agent; recover from test failures likewise.
5. Keep migration modules as pure resource JARs — no Java code required.

### Non-Goals

- Migration-specific logic of any kind in the framework itself.
- A centralized server, database, or cloud service — the tool is self-contained.
- Replacing developer validation or test coverage.

---

## 3. Module Structure

```
Transmute/
  transmute-core/         All Java infrastructure:
                            inventory, planner, workflow, AI agents, CLI, tools
  transmute-dw-helidon/   Pure markdown resources — zero Java, zero deps:
                            recipes and features for Dropwizard 3 → Helidon 4 SE
```

`transmute-dw-helidon` has no production dependencies and is assembled with `transmute-core`
at runtime by placing both JARs on the classpath. It depends on `transmute-core` in test
scope only, for recipe integration tests. The same pattern applies to any migration module.

---

## 4. Pipeline Architecture

```
TransmuteCli (entry point)
  └── MigrationWorkflow (10-step sequential pipeline)
        │
        ├── [1]  CopyProjectTool       copy source → output dir (original untouched)
        ├── [2]  JavaProjectVisitor    build ProjectInventory (Java types, imports,
        │                              annotations, supertypes)
        ├── [3]  MigrationDiscovery    load *.recipe.md / *.feature.md from classpath
        ├── [4]  MigrationPlanner      evaluate triggers; resolve target files;
        │                              deterministic sort by order (then name);
        │                              derive scope (FILE vs PROJECT)
        ├── [5]  ApprovePlan           human gate (skipped if --auto-approve)
        │
        ├── [6]  ExecuteMigrations
        │         ├── FILE-scoped recipes  → one agent call per file
        │         │     (all recipes targeting the same file merged into one prompt)
        │         ├── PROJECT-scoped migrations → one agent call for the whole output dir
        │         │                              (can modify multiple files)
        │         └── All agents append to migration-journal.md (cross-recipe context)
        │
        ├── [7]  ReviewGate            human gate (skipped if --auto-approve)
        │
        ├── [8]  CompileFixLoop        mvn compile → FixCompileErrorsAgent (max 5 iterations)
        │                              (reads migration-journal.md for context)
        ├── [9]  TestFixLoop           mvn test    → FixTestFailuresAgent  (max 5 iterations)
        │                              (reads migration-journal.md for context)
        └── [10] GenerateReport        writes migration-report.json
```

---

## 5. Migrations

All migrations are markdown files loaded from the classpath. There are two kinds:

**Recipe** (`type: recipe`) — A standalone migration unit. A given file matches at most
one recipe. Scope is derived from triggers: FILE if any trigger uses `imports`,
`annotations`, `superTypes`, or `files`; PROJECT otherwise.

**Feature** (`type: feature`) — A cross-cutting fragment. A feature is merged into
whichever recipe(s) share the same target file, so multiple features can compose with
one recipe in a single AI call. Scope is derived from triggers by the same rules as
recipes. Features typically target file-level signals and therefore run at FILE scope.

### Front-matter reference

```yaml
---
name: My Migration        # required, unique; used for display/logs
type: recipe              # recipe | feature
order: 20                 # integer, default 50; lower = runs first
triggers:
  - imports: [com.example.OldClass]        # file imports any of these
    annotations: [com.example.OldAnnot]    # file uses any of these annotations
    superTypes: [com.example.OldBase]      # file extends/implements any of these
  - files: [pom.xml, build.gradle]         # project root contains any of these files
transforms:
  annotations: [com.example.OldAnnot]     # FQN ownership — used by features for conflict detection
  types: [com.example.OldBase]
postchecks:
  forbidImports: [com.example.OldClass]    # warn if target file still imports these
  requireImports: [com.example.NewClass]   # warn if target file does not import these
  forbidPatterns: ["OldApi\\.deprecated"]  # warn if target file matches any listed regex
---

<markdown body — becomes the AI system prompt>
```

Multiple trigger objects are OR'd — the migration fires if **any** trigger matches.
Fields within one trigger object are AND'd — all conditions must hold.

### Scope derivation

| Trigger types present | Derived scope |
|-----------------------|---------------|
| Any `imports` / `annotations` / `superTypes` | FILE (one call per matching file) |
| Any `files` trigger | FILE (one call per matched file path) |
| No file-targeting trigger (or no triggers) | PROJECT (one call for the whole output dir) |

### Combined prompt for FILE-scoped recipes

When multiple recipes target the same file, `MigrationWorkflow` builds one merged system
prompt so the AI can apply all transformations atomically without recipes overwriting
each other:

```
You are an expert Java developer executing a framework migration.
Apply ALL sections below. Each section declares what it owns.
Do not modify anything not covered by a section below.

## Recipe A (owns: @OldAnnotation)
DO NOT touch: OldBaseClass (handled by other sections)
<Recipe A body>

## Recipe B (owns: OldBaseClass)
DO NOT touch: @OldAnnotation (handled by other sections)
<Recipe B body>
```

The `owns:` and `DO NOT touch:` lines are derived from the recipe's `annotations:` and
`superTypes:` trigger conditions.

---

## 6. Trigger System

`MigrationPlanner` evaluates each trigger object against the `ProjectInventory`:

- **`imports`**: at least one Java file imports a matching prefix.
- **`annotations`**: at least one Java file uses a matching annotation FQN.
- **`superTypes`**: at least one Java file extends/implements a matching type FQN.
- **`files`**: the named file exists in `inventory.getRootDir()`.
- **`signals`**: all listed signals are present in `inventory.getSignals()`.
- **`compileErrors`**: all listed regex patterns match at least one compile error string
  (used for error-triggered migrations).

For FILE-scoped triggers, the planner also builds the list of matching target files,
which drives the per-file agent loop in `MigrationWorkflow`.

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
  Migration.java              Planning interface: name(), order()
  AiMigration.java            Loaded markdown migration (implements Migration + AiMigrationMetadata)
  AiMigrationMetadata.java    Full migration metadata: triggers, postchecks, scope, body, etc.
  MigrationResult.java        Result: success flag, message, list of FileChanges
  FileChange.java             Before/after content of one file
  Workspace.java              Path helpers: sourceDir, outputDir, dryRun flag
  MigrationScope.java         Enum: FILE | PROJECT
  MarkdownTrigger.java        Record: imports, annotations, superTypes, signals, compileErrors, files
  MarkdownPostchecks.java     Record: forbidImports, requireImports, forbidPatterns
  postcheck/
    PostcheckRunner.java      Evaluates postchecks after FILE-scoped migrations
    PostcheckRule.java        One check (lambda over FileChange)
    PostcheckResult.java      Pass/fail with description

io.transmute.catalog/
  MigrationPlanner.java       Evaluates triggers; builds sorted, scoped MigrationPlan
  MigrationPlan.java          Ordered list of MigrationExecutionEntry (migration + targetFiles + confidence)
  MarkdownMigrationLoader.java Scans classpath for markdown migrations; parses front-matter
  RecipeFrontMatter.java      Jackson-mapped front-matter record
  MigrationConfidence.java    Enum: LOW (all AI-driven migrations are LOW confidence)
  FeatureConflictException.java Thrown on overlapping feature transform ownership

io.transmute.inventory/
  ProjectInventory.java       Collected project metadata (Java files, root dir, warnings, errors)
  JavaFileInfo.java           Per-file metadata (path, imports, annotations, supertypes)
  JavaProjectVisitor.java     OpenRewrite visitor that populates ProjectInventory

io.transmute.tool/
  FileOperationsTool.java     @Tool: read, write, append, list files (used by AI agents)
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
| 6 — Recipe/Feature execution | **Yes** | `SingleFileAgent` (FILE) or project-scope agent |
| 7 | No | Human gate |
| 8 — Compile fix | **Yes** | `FixCompileErrorsAgent` with file I/O tools |
| 9 — Test fix | **Yes** | `FixTestFailuresAgent` with file I/O tools |
| 10 | No | JSON report |

### Prompt logging

Set `TRANSMUTE_LOG_PROMPTS=true` (default). All prompts and responses are written to
`logs/ai-prompts.jsonl` as newline-delimited JSON via `PromptLogListener`.

---

## 9. Ordering

Migrations are sorted by:

1. **`order:`** — numeric priority. Lower values run first. Default: 50.
2. **`name:`** — lexical tie-breaker for deterministic ordering when orders are equal.

---

## 10. Postcheck Rules

After each FILE-scoped migration completes, `PostcheckRunner` evaluates the postchecks
declared in the front-matter against the file's after-content:

| Rule type | Description |
|-----------|-------------|
| `forbidImports` | Warns if the output file still imports any listed prefix |
| `requireImports` | Warns if the output file does not import any listed prefix |
| `forbidPatterns` | Warns if the output file matches any listed regex |

Failures are logged to the console as warnings (yellow `⚠`). They do not abort the
pipeline — they flag files that may need manual review.

---

## 11. Migration Journal

The Migration Journal (`migration-journal.md`) is an append-only file in the output
directory that provides cross-recipe and cross-agent context throughout the pipeline.

**How it works:**

1. Each recipe/feature agent appends a summary line after completing its changes —
   what it migrated, which file(s) it changed, and any decisions or edge cases.
2. The compile-fix and test-fix agents receive the full journal as context in their
   user message, helping them understand what transformations were applied and why.
3. Fix agents also append their own entries after resolving errors.

**Why it matters:**

Without shared context, fix agents may revert to source-framework patterns when fixing
compile errors because they don't know what the migration intended. The journal bridges
this gap — it tells the fix agent "this class was migrated from X to Y, these imports
were intentionally replaced" so fixes stay consistent with the migration strategy.

The journal is written via the `append_file` tool on `FileOperationsTool`, which
creates the file on first use and appends subsequent entries.

---

## 12. Adding a New Migration Module


1. Create a Maven project with no dependencies.
2. Create `src/main/resources/recipes/` and/or `src/main/resources/features/`.
3. Write `.recipe.md` or `.feature.md` files with front-matter + AI prompt body.
4. Build the JAR and place it on the classpath alongside `transmute-core-*.jar`:

```bash
java -cp transmute-core-1.0-SNAPSHOT.jar:my-migration-1.0.jar \
     io.transmute.agent.TransmuteCli \
     --project-dir /path/to/project \
     --model-provider openai \
     --api-key $OPENAI_API_KEY
```

`MigrationDiscovery` loads all markdown files from `recipes/` and `features/` on the
classpath automatically — no registration or configuration needed.
