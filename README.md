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
3. Discover migrations: load *.recipe.md / *.feature.md files from the classpath
4. Plan: evaluate trigger conditions against the inventory; resolve target files;
   sort by order / after dependency constraints; derive execution scope
5. Human approval gate — the plan is shown before anything is executed
6. Execute migrations in order:
     • Recipes (FILE scope) — one AI agent call per file, all matching recipes merged
     • Features (PROJECT scope) — one AI agent call for the whole output directory
7. Human review gate
8. Compile-fix loop: AI agent fixes compile errors (max 5 iterations)
9. Test-fix loop: AI agent fixes test failures (max 5 iterations)
10. Generate a migration report (JSON)
```

Migrations are plain `.recipe.md` or `.feature.md` files. The front-matter declares
the trigger conditions, execution order, and postchecks. The body is the AI system prompt.

```markdown
---
name: My Recipe
type: recipe          # recipe = FILE scope, feature = PROJECT scope
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
  transmute-dw-helidon/ Pure markdown: recipes and features for Dropwizard 3 → Helidon 4 SE
```

`transmute-dw-helidon` contains **zero Java** — only `.recipe.md` and `.feature.md` resource
files. It has no Maven dependencies. Migration modules are assembled at runtime by placing
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
     --api-key $OPENAI_API_KEY \
     --auto-approve
```

**Windows**
```bat
java -cp transmute-core.jar;transmute-dw-helidon.jar ^
     io.transmute.agent.TransmuteCli ^
     --project-dir  C:\path\to\my-dropwizard-app ^
     --output-dir   .tmp\transmuted ^
     --model-provider openai ^
     --api-key %OPENAI_API_KEY% ^
     --auto-approve
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
| `--auto-approve` | false | Skip human approval/review gates |
| `--dry-run` | false | Compute changes but do not write files |
| `--allow-order-conflicts` | false | Warn instead of fail on ordering violations |

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

**Front-matter fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Human-readable migration name |
| `type` | yes | `recipe` (FILE scope) or `feature` (PROJECT scope) |
| `order` | no | Execution order, default 50 |
| `after` | no | List of migration names this must run after |
| `triggers` | no | List of trigger conditions (omit → always triggered) |
| `postchecks` | no | `forbidImports`, `requireImports` post-execution assertions |

**Trigger types:**

```yaml
triggers:
  - imports: [com.example.OldClass]       # file imports any of these
    annotations: [com.example.OldAnnot]   # file uses any of these annotations
    superTypes: [com.example.OldBase]     # file extends/implements any of these
  - files: [pom.xml, build.gradle]        # project root contains any of these files
```

Scope is derived automatically: FILE if any trigger uses `imports`, `annotations`, or
`superTypes`; PROJECT if triggers use only `files` (or there are no triggers).

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

See [transmute-dw-helidon/README.md](transmute-dw-helidon/README.md) for a worked example
covering build system migration, JAX-RS annotation rewriting, and HealthCheck migration.

See [PROJECT.md](PROJECT.md) for the full design: trigger system, scope derivation,
AI prompt composition, postcheck rules, and all architectural decisions.
