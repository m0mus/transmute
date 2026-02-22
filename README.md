# Transmute

A domain-agnostic Java code migration framework. It provides the orchestration
infrastructure — skill discovery, project inventory, planning, execution, and an
AI-assisted compile-fix loop — for migrating Java projects from one API or framework
to another. All migration knowledge lives in separate skill modules; the core contains
none.

For the full design, rationale, and API reference see [PROJECT.md](PROJECT.md).

---

## How it works

```
1. Copy project to an output directory (original is untouched)
2. Scan the project: build a ProjectInventory of all Java types, imports, annotations,
   dependencies, and inter-module relationships
3. Discover MigrationSkill classes on the classpath via ClassGraph
4. Plan: evaluate skill trigger conditions against the inventory; resolve target files;
   sort by @Skill(order=...) with @Skill(after=...) constraints
5. Human approval gate — the plan is shown before anything is executed
6. Execute skills in order; run PostcheckRunner on AI-backed skills
7. Human review gate
8. Compile-fix loop: classify errors (SKILL_GAP / COMPILE_ONLY / NOVEL), re-run
   responsible skills, then use an AI agent for genuinely unknown problems
9. Test-fix loop: run tests; use an AI agent for failures
10. Generate a migration report (JSON + git-format patch in dry-run mode)
```

Skills are plain annotated Java classes. There is no registry, no configuration file,
and no changes to the framework when a new skill is added:

```java
@Skill(value = "Migrate JAX-RS annotations", order = 20)
@Trigger(imports = {"javax.ws.rs.", "jakarta.ws.rs."})
@Postchecks(forbidImports = {"javax.ws.rs.", "jakarta.ws.rs."})
public class JaxrsAnnotationsSkill implements MigrationSkill {
    @Override
    public SkillResult apply(SkillContext ctx) throws Exception { ... }
}
```

---

## Module layout

```
Transmute/
  transmute-core/           Skill API, inventory, planner, compile analysis, tools
  transmute-core-testkit/   Test helpers: SkillTestHarness, SourceTypeRegistryVerifier
  transmute-agent/          Agentic workflow (LC4j), CLI entry point, prompt resources
  transmute-dw-helidon/     Skills for Dropwizard 3 → Helidon 4 SE migrations
```

`transmute-core` and `transmute-core-testkit` have no dependency on any specific
migration. Skill modules (like `transmute-dw-helidon`) depend only on
`transmute-core`. The agent assembles the full pipeline at runtime by scanning
whichever skill JARs are on the classpath.

### Key packages in transmute-core

| Package | Contents |
|---------|----------|
| `io.transmute.inventory` | `ProjectInventory`, `JavaFileInfo`, `JavaProjectVisitor` |
| `io.transmute.skill` | `MigrationSkill`, `SkillContext`, `SkillResult`, `FileChange`, `Workspace` |
| `io.transmute.skill.annotation` | `@Skill`, `@Trigger`, `@Postchecks`, `@Fallback`, `SkillScope` |
| `io.transmute.skill.trigger` | `TriggerCondition`, `TriggerPredicates` |
| `io.transmute.skill.postcheck` | `PostcheckRunner`, `PostcheckRule`, `PostcheckResult` |
| `io.transmute.catalog` | `SkillDiscovery`, `MigrationPlanner`, `MigrationPlan`, `SourceTypeRegistry` |
| `io.transmute.compile` | `CompileErrorParser`, `CompileErrorAnalyzer`, `CompileError` |
| `io.transmute.tool` | `@Tool` implementations for AI agents (file I/O, compile, test) |

---

## Prerequisites

- Java 21+
- Maven 3.9+

## Building

```bash
mvn clean package -DskipTests
```

All four modules (`transmute-core`, `transmute-core-testkit`, `transmute-agent`,
`transmute-dw-helidon`) are built in a single reactor invocation.

---

## Running a migration

**Linux / macOS**
```bash
java -cp transmute-agent.jar:transmute-dw-helidon.jar \
     io.transmute.agent.AgentRunner \
     --project-dir  /path/to/my-dropwizard-app \
     --output-dir   /path/to/my-dropwizard-app-migrated \
     --skills-package io.transmute.dw \
     --model-provider openai \
     --api-key $OPENAI_API_KEY
```

**Windows**
```bat
java -cp transmute-agent.jar;transmute-dw-helidon.jar ^
     io.transmute.agent.AgentRunner ^
     --project-dir  C:\path\to\my-dropwizard-app ^
     --output-dir   C:\path\to\my-dropwizard-app-migrated ^
     --skills-package io.transmute.dw ^
     --model-provider openai ^
     --api-key %OPENAI_API_KEY%
```

### Key options

| Flag | Default | Description |
|------|---------|-------------|
| `--project-dir` | *(required)* | Source project to migrate |
| `--output-dir` | `<project-dir>-transmuted` | Where the migrated copy is written |
| `--skills-package` | *(required)* | Package prefix(es) to scan for skills |
| `--profile` | *(none)* | Maven profile(s) to activate (repeatable) |
| `--model-provider` | `oci-genai` | AI provider: `oci-genai`, `openai`, `ollama` |
| `--model-id` | *(provider default)* | Model identifier |
| `--auto-approve` | false | Skip human approval/review gates |
| `--dry-run` | false | Compute changes but do not write; produces a `.patch` file |
| `--verbose` | false | Log every tool call made by AI agents |
| `--allow-order-conflicts` | false | Warn instead of fail on `@Skill(after=...)` violations |

### Environment variables

`TRANSMUTE_MODEL_PROVIDER`, `TRANSMUTE_MODEL_ID`, `TRANSMUTE_API_KEY`,
`TRANSMUTE_MODEL_BASE_URL`, `TRANSMUTE_MODEL_TIMEOUT_SECONDS`,
`TRANSMUTE_AUTO_APPROVE`, `TRANSMUTE_VERBOSE`, `TRANSMUTE_DRY_RUN`,
`TRANSMUTE_LOG_PROMPTS`

---

## Writing a skill module

1. Create a Maven project that depends on `transmute-core`.
2. Implement `MigrationSkill`, annotate with `@Skill` and `@Trigger`.
3. Implement `SourceTypeRegistry` so the compile-fix loop can classify errors.
4. Add a test using `SkillTestHarness` and `SourceTypeRegistryVerifier` (from `transmute-core-testkit`).
5. Place the JAR on the classpath alongside `transmute-agent.jar` and pass
   `--skills-package your.package` at the command line.

See `transmute-dw-helidon/` for a worked example covering POM migration, JAX-RS
annotation rewriting, Dropwizard orphan handling, and HealthCheck migration.

See [PROJECT.md](PROJECT.md) for the full design: skill annotations, trigger
predicates, postcheck rules, AI-backed skill patterns, compile error classification,
and all architectural decisions.
