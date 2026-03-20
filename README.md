# Transmute

A domain-agnostic Java code migration framework. It provides the orchestration
infrastructure — migration discovery, project inventory, planning, execution, and an
AI-assisted compile-fix loop — for migrating Java projects from one API or framework
to another. All migration knowledge lives in separate migration modules; the core contains
none.

For the full design, rationale, and API reference see [PROJECT.md](PROJECT.md).

---

## How it works

```
 1. Copy project to an output directory (original is untouched)
 2. Scan the project: build a ProjectInventory of all Java types, imports, annotations,
    and inter-module relationships
 3. Analyze project: AI reads inventory + key files → structured summary
    (prepended to all recipe prompts for broader context)
 4. Discover migrations: load *.recipe.md / *.feature.md files from the classpath
 5. Plan: evaluate trigger conditions against the inventory; resolve target files;
    sort by order; show file-centric plan; save migration-plan.txt
 6. Execute migrations in order:
      • Recipes (FILE scope) — one AI agent call per file, all matching recipes merged
      • Project-scoped migrations — one AI agent call for the whole output directory
        (can modify multiple files)
      • Each agent appends to a migration journal for cross-recipe context
 7. Scan for TRANSMUTE TODOs: collect TRANSMUTE[CATEGORY] markers left in migrated files;
    save migration-todos.txt
 8. Compile-fix loop: AI agent fixes compile errors (max 5 iterations)
     — reads migration journal for context on what was changed and why
 9. Test-fix loop: AI agent fixes test failures (max 5 iterations)
     — reads migration journal for context on what was changed and why
10. Generate a migration report: migration-report.json + migration-results.txt;
    print a styled console summary (outcomes, file results, TODOs, postcheck failures)
```

Migrations are plain `.recipe.md` or `.feature.md` files. The front-matter declares
the trigger conditions, execution order, and postchecks. The body is the AI system prompt.

```markdown
---
name: My Recipe
type: recipe          # kind only; scope is derived from triggers
order: 20
triggers:
  - imports: [com.example.OldApi]
postchecks:
  forbidImports: [com.example.OldApi]
---

Migrate usages of OldApi to NewApi. Replace ...
```

---

## Module layout

```
Transmute/
  transmute-core/       All Java: inventory, planner, workflow,
                        AI agents, CLI entry point, tool implementations
  transmute-dw-helidon/ Pure markdown: recipes, features, and hints for Dropwizard 3 → Helidon 4 SE
```

`transmute-dw-helidon` contains **zero production Java** — only `.recipe.md`,
`.feature.md`, and `.md` hint resource files. It depends on `transmute-core` for
test-scope integration tests only. Migration modules are assembled at runtime by placing
the two JARs on the classpath together.

### Key packages in transmute-core

| Package | Contents |
|---------|----------|
| `io.transmute.inventory` | `ProjectInventory`, `JavaFileInfo`, `JavaProjectVisitor` |
| `io.transmute.migration` | `AiMigration`, `AiMigrationMetadata`, `MigrationResult`, `FileChange`, `Workspace`, `MarkdownTrigger`, `MarkdownPostchecks`, `MigrationScope` |
| `io.transmute.migration.postcheck` | `PostcheckRunner`, `PostcheckRule`, `PostcheckResult` |
| `io.transmute.catalog` | `MigrationDiscovery`, `MigrationPlanner`, `MigrationPlan`, `MarkdownMigrationLoader`, `RecipeFrontMatter` |
| `io.transmute.agent` | `TransmuteCli`, `TransmuteConfig`, `ModelFactory` |
| `io.transmute.agent.workflow` | `MigrationWorkflow` |
| `io.transmute.agent.agent` | `FixCompileErrorsAgent`, `FixTestFailuresAgent` |
| `io.transmute.tool` | `@Tool` implementations for AI agents (file I/O, compile, test, copy) |

---

## Prerequisites

- Java 21+
- Maven 3.9+

## Building

```bash
mvn clean package -DskipTests
```

Both modules (`transmute-core`, `transmute-dw-helidon`) are built in a single reactor
invocation.

## Running integration tests

Recipe integration tests live in `transmute-dw-helidon` and require a real AI model.
Set `TRANSMUTE_API_KEY` (and optionally `TRANSMUTE_MODEL_PROVIDER`) then run:

**Linux / macOS**
```bash
TRANSMUTE_API_KEY=sk-... mvn clean test --also-make -pl transmute-dw-helidon -Pit
```

**Windows (PowerShell)**
```powershell
$env:TRANSMUTE_API_KEY = "sk-..."
mvn clean test --also-make -pl transmute-dw-helidon -Pit
```

IT tests only run with the `-Pit` profile. Without it, `mvn test` skips them entirely.

---

## Running a migration

The recommended way is to use the bundled scripts:

**Linux / macOS**
```bash
bash scripts/run.sh /path/to/my-dropwizard-app
```

**Windows (PowerShell)**
```powershell
.\scripts\run.ps1 -ProjectDir C:\path\to\my-dropwizard-app
```

Or invoke directly:

**Linux / macOS**
```bash
CORE_JAR=transmute-core/target/transmute-core-1.0-SNAPSHOT.jar
RECIPES_JAR=transmute-dw-helidon/target/transmute-dw-helidon-1.0-SNAPSHOT.jar

java -cp "$CORE_JAR:$RECIPES_JAR" \
     io.transmute.agent.TransmuteCli \
     --project-dir  /path/to/my-dropwizard-app \
     --output-dir   .tmp/transmuted \
     --model-provider openai \
     --api-key $OPENAI_API_KEY
```

**Windows**
```bat
java -cp transmute-core.jar;transmute-dw-helidon.jar ^
     io.transmute.agent.TransmuteCli ^
     --project-dir  C:\path\to\my-dropwizard-app ^
     --output-dir   .tmp\transmuted ^
     --model-provider openai ^
     --api-key %OPENAI_API_KEY%
```

### Key options

| Flag | Default | Description |
|------|---------|-------------|
| `--project-dir` | *(required)* | Source project to migrate |
| `--output-dir` | `.tmp/transmuted` | Where the migrated copy is written |
| `--model-provider` | `openai` | AI provider: `openai`, `oci-genai`, `ollama` |
| `--model-id` | *(provider default)* | Model identifier |
| `--api-key` | *(required for openai)* | API key |
| `--base-url` | *(provider default)* | Override base URL (e.g. for local models) |
| `--profile` | *(none)* | Maven profile(s) to activate during compile/test (repeatable) |
| `--dry-run` | false | Compute changes but do not write files |

### Environment variables

| Variable | Description |
|----------|-------------|
| `TRANSMUTE_MODEL_PROVIDER` | `openai`, `oci-genai`, `ollama` |
| `TRANSMUTE_MODEL_ID` | Model identifier |
| `TRANSMUTE_API_KEY` | API key (required for `openai`) |
| `TRANSMUTE_MODEL_BASE_URL` | Override base URL |
| `TRANSMUTE_MODEL_TIMEOUT_SECONDS` | Request timeout in seconds |
| `TRANSMUTE_VERBOSE` | `true`/`false` — log AI tool calls |
| `TRANSMUTE_LOG_PROMPTS` | `true`/`false` — log full prompts to `logs/ai-prompts.jsonl` |

---

## Writing a migration module

A migration module is a Maven project with no dependencies. Place `.recipe.md` or
`.feature.md` files under `src/main/resources/recipes/` or `src/main/resources/features/`.
Optionally, add converter hints under `src/main/resources/hints/`.

**Converter hints** (optional): place `hints/compile-hints.md` and/or
`hints/test-hints.md` under `src/main/resources/hints/`. The framework loads
and concatenates hints from all JARs on the classpath and injects them into
the compile-fix and test-fix agents.

**Front-matter fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Human-readable migration name |
| `type` | yes | `recipe` or `feature` (scope is derived from triggers) |
| `order` | no | Execution order, default 50 |
| `triggers` | no | List of trigger conditions (omit → always triggered) |
| `transforms` | no | `annotations` and `types` lists declaring FQN ownership (used by features for conflict detection) |
| `postchecks` | no | `forbidImports`, `requireImports`, `forbidPatterns` post-execution assertions |

**Trigger types:**

```yaml
triggers:
  - imports: [com.example.OldClass]       # file imports any of these
    annotations: [com.example.OldAnnot]   # file uses any of these annotations
    superTypes: [com.example.OldBase]     # file extends/implements any of these
  - files: [pom.xml, build.gradle]        # project root contains any of these files
```

Scope is derived automatically: FILE if any trigger uses `imports`, `annotations`,
`superTypes`, or `files`; PROJECT only when no file-targeting trigger is present.

Build the JAR and place it on the classpath alongside `transmute-core-*.jar`:

```bash
java -cp transmute-core-1.0-SNAPSHOT.jar:my-migration-1.0.jar \
     io.transmute.agent.TransmuteCli \
     --project-dir /path/to/project \
     --model-provider openai \
     --api-key $OPENAI_API_KEY
```

No registration or configuration needed — `MigrationDiscovery` loads all markdown files
from `recipes/` and `features/` on the classpath automatically.

---

See [transmute-dw-helidon/README.md](transmute-dw-helidon/README.md) for the full
Dropwizard→Helidon migration reference: all migrations, what each one does, and what
requires manual follow-up.

See [PROJECT.md](PROJECT.md) for the full design: trigger system, scope derivation,
AI prompt composition, postcheck rules, and all architectural decisions.
