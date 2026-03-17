# Java Code Migration Framework -- Project Proposal

## 1. Overview

A framework for migrating Java codebases from one API or framework to another.
The framework provides orchestration, project inventory analysis, a skill execution
pipeline, and AI-assisted transformation. Users define skills -- self-contained
migration units -- for their specific source and target frameworks. The framework
discovers and runs them automatically from the classpath.

The framework contains no migration knowledge of its own. Domain knowledge lives
entirely in skill projects. The same infrastructure supports any Java-to-Java
migration a skill author chooses to define.

---

## 2. Goals

1. Provide a reusable, domain-agnostic migration orchestration pipeline.
2. Make skill development easy -- one annotated class per skill, no registration,
   no changes to the framework.
3. Combine deterministic OpenRewrite transforms with AI-backed transforms in a
   single unified skill model.
4. Make the migration plan visible and auditable before execution (human-in-the-loop).
5. Handle compile errors intelligently -- classify first, use AI only for genuinely
   unknown problems.
6. Allow the skill catalog to grow through a skill generator and a simple
   contribution workflow.

### Non-Goals

- Migration-specific logic of any kind in the framework itself.
- A centralized server, database, or cloud service -- the tool is self-contained.
- Replacing developer validation or test coverage.

---

## 3. Project Structure

Three separate Maven projects:

```
migration-framework/              pure infrastructure -- zero migration knowledge
  migration-core/                 inventory, skill annotations, discovery, planner
  migration-agent/                agentic workflow, skill generator, CLI entry point

dw-helidon-skills/                separate project -- Dropwizard -> Helidon 4 skills
  depends on: migration-core only

user-defined-skills/              any other migration -- same pattern
  depends on: migration-core only
```

The framework and skill projects are independently versioned and released.
Users assemble the tool by placing the framework JARs and their chosen skill JARs
on the classpath together.

---

## 4. Architecture Overview

```
migration-agent (entry point -- LC4j Agentic Workflow)
  |
  +-- MigrationWorkflow (SequenceAgent)
        |
        +-- Non-AI: CopyProject
        +-- Non-AI: GenerateSources         <- mvn generate-sources (annotation processors,
        |                                      code-gen plugins); output paths added to scan
        +-- Non-AI: CaptureDependencyTree   <- mvn dependency:tree; full transitive graph
        |                                      stored in ProjectInventory for AI context
        +-- Non-AI: ScanInventory           <- JavaProjectVisitor builds ProjectInventory
        |                                      (Java source files only; generated sources
        |                                      are marked as annotation-processor output)
        +-- Non-AI: BuildMigrationPlan      <- MigrationPlanner evaluates skill triggers
        +-- HumanInTheLoop: ApprovePlan     <- user sees what runs on what
        |
        +-- Non-AI: ExecuteSkills           <- orchestrator runs discovered skills in order
        |     Skill (order=10, OpenRewrite)
        |     Skill (order=20, OpenRewrite)
        |     Skill (order=50, AI-backed)
        |     Skill (order=90, project-scope)
        |     Non-AI: RefreshInventory      <- re-scan outputDir if any skill generated/renamed files
        |
        +-- HumanInTheLoop: ReviewChanges
        |
        +-- LoopAgent: CompileFixLoop       <- progress-based exit
        |     Non-AI: Compile
        |     Non-AI: ClassifyErrors        <- skill gap / compile-only / novel
        |     Non-AI: RetrySkillGaps        <- re-run responsible skill on gap errors
        |     Non-AI: RunErrorSkills        <- error-triggered skills (order=200+)
        |     AI Agent: FixNovelErrors      <- AI sees only genuinely unknown errors
        |     onStuck: HumanInTheLoop escalation
        |
        +-- LoopAgent: TestFixLoop
        |     Non-AI: RunTests
        |     AI Agent: FixTestFailures
        |     onStuck: HumanInTheLoop escalation
        |
        +-- Non-AI: GenerateReport
```

---

## 5. Module Structure

### migration-core

```
io.migration/
  inventory/
    JavaFileInfo.java
    DependencyInfo.java
    ProjectInventory.java
    JavaProjectVisitor.java         single generic OpenRewrite visitor
  skill/
    MigrationSkill.java             interface: apply(SkillContext) -> SkillResult
    SkillContext.java
    Workspace.java                  I/O path helpers: outputDir, sourceDir, sourceFileFor, isDryRun
    SkillResult.java
    FileChange.java
    AiRetryGuard.java               runaway-rewrite guard: maxDeltaLines / maxOutputBytes
    annotations/
      Skill.java
      Trigger.java
      Triggers.java
      Postchecks.java
      Fallback.java
      PostcheckPolicy.java
      FallbackType.java
    trigger/
      TriggerCondition.java
      TriggerPredicates.java
    postcheck/
      PostcheckResult.java
      PostcheckRule.java
  catalog/
    SkillDiscovery.java             ClassGraph-based runtime discovery
    MigrationPlanner.java
    MigrationPlan.java
    ProjectState.java               inter-skill state / discoveries
    MigrationLog.java
    SourceTypeRegistry.java         interface -- skill projects provide known source types
  compile/
    CompileErrorAnalyzer.java
    CompileErrorParser.java         parses raw compiler output; enriches rawSymbol -> resolvedFqn
    CompileError.java
    ErrorAnalysis.java
    ErrorClass.java
  tool/
    FileOperationsTool.java         @Tool for AI agents: read, write, list
    CompileProjectTool.java
    RunTestsTool.java
    ValidationTool.java
    CopyProjectTool.java
    MigrationTools.java             registry of low-level tools
  validation/
    SyntaxValidator.java
    PatternValidator.java

depends on: rewrite-java, rewrite-maven, langchain4j-core,
            jackson-databind, classgraph, slf4j-api
```

### migration-agent

```
io.migration.agent/
  MigrationWorkflow.java            assembles the full SequenceAgent pipeline
  AgentRunner.java                  CLI entry point
  ModelFactory.java                 configurable LLM provider factory
  agent/
    FixCompileErrorsAgent.java      AI agent: fixes novel compile errors
    FixTestFailuresAgent.java       AI agent: fixes test failures
    SkillGeneratorAgent.java        AI agent: generates skills from before/after
  cli/
    GenerateSkillCommand.java       migration-agent generate-skill
src/main/resources/
  prompts/                          system prompts for framework AI agents

depends on: migration-core, langchain4j-agentic,
            langchain4j-community-oci-genai (+ optional providers)
```

### dw-helidon-skills (separate project)

```
io.migration.skills.dw/
  DwSourceTypeRegistry.java         implements SourceTypeRegistry -- DW known types
  DependencyMigrationSkill.java
  ImportMigrationSkill.java
  AnnotationMigrationSkill.java
  HealthCheckMigrationSkill.java
  MetricsMigrationSkill.java
  ClientMigrationSkill.java
  ProviderMigrationSkill.java
  SecurityAuthSkill.java
  ApplicationMigrationSkill.java
  errors/
    ClasspathRepairSkill.java
    MissingTransitiveDependencySkill.java
  trigger/
    (TriggerCondition implementations for complex DW patterns)
src/main/resources/
  prompts/                           AI agent prompts (DW -> Helidon specific)
  examples/                          Helidon 4 SE code examples for few-shot context
src/test/resources/
  annotation/before/
  annotation/after/
  (before/after pairs for each skill)

depends on: migration-core only
```

---

## 6. ProjectInventory -- Generic Java Project Model

The inventory describes a Java project in neutral terms. It knows nothing about
any specific framework. Skill trigger conditions decide what the facts mean.

```java
public record JavaFileInfo(
    String sourceFile,            // relative path: "src/main/java/com/example/Foo.java"
    String className,             // FQN: "com.example.Foo"
    Set<String> annotationTypes,  // ALL FQN annotations: class + method + field + param
    Set<String> imports,          // FQN imports declared in the file
    Set<String> superTypes,       // FQN: both extends and implements
    Map<String, String> symbolMap // simpleName -> FQN for every type reference OpenRewrite
                                  // resolved during parsing (e.g. "Response" -> "javax.ws.rs.core.Response")
                                  // Primary FQN source for CompileErrorParser; avoids
                                  // fragile regex reverse-index for files that parsed cleanly
) {}

public record DependencyInfo(
    String groupId,
    String artifactId,
    String version,
    String scope
) {}

/**
 * Represents one Maven module in a multi-module project.
 * For single-module projects the list contains one entry.
 * Modules are stored in topological order (no module appears before its dependencies).
 * CompileProjectTool and RunTestsTool respect this order when invoking Maven per module.
 */
public record ModuleInfo(
    String artifactId,
    String modulePath,          // path relative to project root: "service/user-api"
    List<DependencyInfo> managedDependencies,  // from this module's dependencyManagement
    List<String> dependsOn      // artifactIds of sibling modules this module depends on
) {}

public class ProjectInventory {

    // Data
    String projectName();
    String rootDir();
    List<ModuleInfo> modules();                    // topological order; single entry for non-multi-module
    List<JavaFileInfo> files();                    // all Java source files across all modules
    List<DependencyInfo> dependencies();           // effective direct dependencies (parent + modules)
    List<DependencyInfo> transitiveDependencies(); // full tree from mvn dependency:tree
                                                   // used by AI agent to diagnose classpath conflicts
    List<String> scanWarnings();
    List<String> scanErrors();

    // Query API -- used by trigger conditions and skill apply() bodies
    List<JavaFileInfo> filesWithAnnotationType(String fqn);
    List<JavaFileInfo> filesExtending(String fqn);
    List<JavaFileInfo> filesImplementing(String fqn);
    List<JavaFileInfo> filesWithImport(String regexPattern);
    List<JavaFileInfo> filesMatching(Predicate<JavaFileInfo> predicate);
    boolean hasAnnotationType(String fqn);
    boolean hasDependency(String groupId);
    Optional<String> dependencyVersion(String groupId);
}
```

A single `JavaProjectVisitor` populates the inventory using OpenRewrite's type
attribution. All annotation types, imports, and super types are captured as FQN.
No domain knowledge lives in the visitor. For multi-module projects the visitor
runs per module in topological order, merging results into a single `ProjectInventory`.
Dependency resolution follows Maven's effective-POM rules: parent `dependencyManagement`
provides defaults, module-level declarations override them.

---

## 7. Skill System

### 7.1 The MigrationSkill Interface

```java
public interface MigrationSkill {
    SkillResult apply(SkillContext context);
}
```

One method to implement. All other behaviour -- when to run, ordering, validation,
fallback -- is declared via annotations.

### 7.2 Annotations

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Skill {
    String value();                        // description -- shown in plan, logs, report
    int order() default 50;               // execution order: lower runs first
                                          // convention: 1-20 structural, 30-70 feature,
                                          //             80-90 project-scope, 200+ error-triggered
    SkillScope scope() default SkillScope.FILE; // FILE or PROJECT -- controls planner behaviour
    Class<? extends MigrationSkill>[] after() default {};
                                          // explicit ordering constraints -- planner
                                          // rejects a plan where any listed skill would
                                          // run after this one; enforced across JARs
    Class<? extends SkillFactory<?>> factory() default SkillFactory.None.class;
                                          // optional factory for constructor injection
                                          // (model selection, prompt loading, test doubles)
}

public enum SkillScope {
    FILE,     // planner pre-resolves targetFiles(); skill iterates them independently
    PROJECT   // planner sets targetFiles() to empty; skill uses ctx.inventory() directly
}
```

```java
/**
 * Optional factory for skills that need constructor injection.
 * SkillDiscovery calls create() if a factory is declared; otherwise it uses
 * the no-arg constructor. Enables injecting test doubles without static singletons.
 */
public interface SkillFactory<T extends MigrationSkill> {
    T create(SkillConfig config);
    interface None extends SkillFactory<MigrationSkill> {} // sentinel -- no factory
}

/** Immutable config passed to SkillFactory.create(). */
public record SkillConfig(
    ChatModel model,
    Path promptsDir,
    Map<String, String> properties,
    int maxDeltaLines,   // AI retry guard: max changed lines per file (default: 500)
    int maxOutputBytes   // AI retry guard: max output size in bytes (default: 200_000)
) {}
```

```java
// OR semantics -- any matching @Trigger activates the skill
@Repeatable(Triggers.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Trigger {
    String importPattern()  default ""; // regex on FQN import
    String annotationType() default ""; // exact FQN annotation class name
    String compileError()   default ""; // regex on compiler error message
    Class<? extends TriggerCondition> condition() default TriggerCondition.None.class;
}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Triggers {
    Trigger[] value();
}
```

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Postchecks {
    String[] forbidImports()     default {};
    String[] forbidAnnotations() default {};
    String[] forbidPatterns()    default {};
    String[] requireTodos()      default {};
    PostcheckPolicy onFail()     default PostcheckPolicy.ROLLBACK;
    Class<? extends PostcheckRule>[] rules() default {};
}

public enum PostcheckPolicy { ROLLBACK, WARN, INVOKE_FALLBACK }
```

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Fallback {
    FallbackType type()  default FallbackType.AI;
    String promptRef()   default "";
    Class<? extends MigrationSkill> skillClass() default MigrationSkill.class;
}

public enum FallbackType { AI, SKILL }
```

### 7.3 Writing a Skill -- One Class, No Registration

```java
package io.migration.skills.dw;

@Skill(value = "Migrate JAX-RS annotations to target framework annotations", order = 20)
@Triggers({
    @Trigger(annotationType = "javax.ws.rs.Path"),
    @Trigger(annotationType = "jakarta.ws.rs.Path"),
    @Trigger(importPattern  = "javax\\.ws\\.rs\\..*")
})
@Postchecks(forbidImports = {"javax.ws.rs.*", "jakarta.ws.rs.*"})
public class AnnotationMigrationSkill implements MigrationSkill {

    private final AnnotationMigrationRecipe recipe = new AnnotationMigrationRecipe();

    @Override
    public SkillResult apply(SkillContext ctx) {
        // File-scope: iterate pre-resolved target files independently
        List<FileChange> changes = ctx.targetFiles().stream()
            .map(file -> recipe.transform(file))
            .toList();
        return SkillResult.of(changes);
    }
}
```

The class is discovered automatically. No catalog entry. No registration file.
No changes to the framework.

### 7.4 Complex Triggers -- Composable Predicates

When trigger logic requires AND, NOT, or arbitrary conditions, use a
`TriggerCondition` class with the `TriggerPredicates` fluent API:

```java
@Skill(value = "Migrate auth integration", order = 40)
@Triggers(@Trigger(condition = AuthTriggerCondition.class))
public class AuthMigrationSkill implements MigrationSkill { ... }

public class AuthTriggerCondition implements TriggerCondition {
    @Override
    public Predicate<SkillContext> predicate() {
        return hasAnnotationType("com.example.auth.Auth")
                   .or(hasAnnotationType("javax.annotation.security.RolesAllowed"))
            .and(not(hasAnnotationType("io.target.security.Authenticated")));
    }
}
```

`TriggerPredicates` provides: `hasAnnotationType`, `hasImport`, `hasCompileError`,
`hasDependency`, `dependencyVersion`, `not`. Standard `Predicate` `.and()` / `.or()`
composition handles all logical combinations.

### 7.5 SkillContext

```java
public class SkillContext {
    ProjectInventory inventory();    // full project facts -- always available
    List<String> targetFiles();      // see contract below
    ProjectState projectState();     // discoveries from prior skills
    List<String> compileErrors();    // populated after compile attempts
    ChatModel model();               // for AI-backed skills

    <T> Optional<T> get(String key, Class<T> type); // read prior skill discoveries

    /**
     * Returns all log entries for the given file from prior skill executions,
     * ordered by execution time. Allows a skill to see what previous skills did
     * to the same file -- useful to avoid redundant transforms or detect regressions.
     * Returns an empty list if no prior skill touched the file.
     */
    List<MigrationLogEntry> executionHistory(String outputFile);

    Workspace workspace();           // I/O path helpers -- see below
}

/** Groups all I/O path concerns; keeps the main context focused on facts and state. */
public class Workspace {
    String outputDir();              // migrated copy -- all skill writes go here
    String sourceDir();              // original -- read-only, used for AI context
    boolean isDryRun();              // true when --dry-run flag is set; skills still
                                    // compute FileChange objects but must not write to disk;
                                    // GenerateReport emits a git-format .patch file so
                                    // developers can review proposed changes with standard
                                    // IDE diff tools or "git apply --check"

    /**
     * Maps an absolute outputDir path back to the corresponding absolute sourceDir path.
     * Use in AI skills to provide the original source as intent context alongside the
     * current (partially migrated) version in the prompt.
     *   String original = Files.readString(Path.of(ctx.workspace().sourceFileFor(file)));
     */
    String sourceFileFor(String outputFile);
}
```

**targetFiles() contract:**

- All paths are **absolute** and point into `workspace().outputDir()` (the migrated copy).
  Skills always read from and write to `outputDir`, never to the original source.
- For **file-scope skills** (`@Skill(scope = SkillScope.FILE)`): the planner resolves
  and populates this list before the skill runs. The list contains all files whose
  inventory data matched the skill's trigger. Skills iterate over it independently.
- For **project-scope skills** (`@Skill(scope = SkillScope.PROJECT)`): `targetFiles()`
  is always empty. Project-scope skills are expected to use `ctx.inventory()` directly
  to determine what to process. The planner checks `@Skill(scope=...)` to decide which
  pattern applies; there is no marker interface to implement.

### 7.6 SkillResult

```java
public record SkillResult(
    List<FileChange> changes,        // modified existing files or newly generated files
    Map<String, Object> discoveries  // state published for subsequent skills
) {}

public record FileChange(
    String file,    // absolute outputDir path
    String before,  // content before the skill ran (empty string for generated files)
    String after    // content after the skill ran
) {
    /** True when the skill produced a non-empty diff, regardless of skill type.
     *  Used by MigrationLog to upgrade status from ATTEMPTED to CHANGED.
     *  OpenRewrite and AI skills use the same definition: !before.equals(after). */
    boolean isChanged() { return !before.equals(after); }
}
```

### 7.7 File-Scope vs. Project-Scope Skills

**File-scope** -- processes each pre-resolved target file independently:

```java
@Override
public SkillResult apply(SkillContext ctx) {
    // ctx.targetFiles() contains absolute outputDir paths, pre-resolved by planner
    return SkillResult.of(
        ctx.targetFiles().stream().map(recipe::transform).toList()
    );
}
```

**Project-scope** -- queries the inventory directly in apply(), may generate
new files, publishes discoveries for subsequent skills. Using `inventory()` in
apply() is correct and expected for this pattern:

```java
@Override
public SkillResult apply(SkillContext ctx) {
    // Project-scope: query inventory to determine scope at runtime
    List<JavaFileInfo> services = ctx.inventory().filesImplementing("com.example.Service");
    DependencyGraph graph = DependencyGraphBuilder.build(services);

    // Generate one output file derived from the whole project picture
    String main = MainGenerator.generate(graph);

    return SkillResult.of(
        List.of(FileChange.generated("src/main/java/.../Main.java", main)),
        Map.of("dependencyGraph", graph)   // published for subsequent skills
    );
}
```

Inter-skill data flows through `ProjectState`. The orchestrator accumulates
discoveries after each skill and passes them forward in the next skill's context.
Skills publish and consume without coupling to each other.

---

## 8. Skill Discovery -- Classpath Scanning

Skills are discovered at runtime using `ClassGraph`. No `META-INF/services` files,
no hand-maintained lists, no framework changes when new skills are added.

**Package prefixes are mandatory.** An empty prefix would scan the full classpath,
risking accidental discovery of unintended classes from unrelated dependencies or
triggering class-initialization side effects. The framework requires at least one
explicit prefix, supplied via CLI or configuration:

```java
public class SkillDiscovery {

    /**
     * Discovers all MigrationSkill implementations in the given packages,
     * sorted by @Skill(order=...).
     *
     * @param packagePrefixes at least one prefix is required; e.g.
     *        "io.migration.skills.dw", "com.acme.custom.skills"
     * @throws IllegalArgumentException if no package prefixes are provided
     */
    public static List<MigrationSkill> discover(List<String> packagePrefixes) {
        if (packagePrefixes == null || packagePrefixes.isEmpty()) {
            throw new IllegalArgumentException(
                "At least one skills package prefix is required. " +
                "Provide --skills-package <prefix> on the command line.");
        }

        try (ScanResult scan = new ClassGraph()
                .acceptPackages(packagePrefixes.toArray(String[]::new))
                .enableAnnotationInfo()
                .enableClassInfo()
                .scan()) {

            return scan.getClassesImplementing(MigrationSkill.class)
                .filter(info -> info.hasAnnotation(Skill.class))
                .loadClasses(MigrationSkill.class)
                .stream()
                .map(cls -> instantiate(cls, config))  // factory if declared, else no-arg
                .sorted(Comparator.comparingInt(SkillDiscovery::order))
                .toList();
        }
    }

    private static int order(MigrationSkill skill) {
        Skill a = skill.getClass().getAnnotation(Skill.class);
        return a != null ? a.order() : 50;
    }
}
```

CLI usage -- package prefix is required:

```bash
# Single skill set
java -cp migration-agent.jar:dw-helidon-skills.jar \
     io.migration.agent.AgentRunner \
     --project-dir /path/to/project \
     --skills-package io.migration.skills.dw

# Activate Maven profiles (passed through to all mvn invocations)
# Important: inventory and compile results depend on which profiles are active.
# If the project has profile-conditional dependencies, the wrong profile produces
# misleading inventory and misclassified compile errors.
java -cp ... io.migration.agent.AgentRunner --project-dir /path/to/project \
     --skills-package io.migration.skills.dw --active-profiles prod,helidon

# Multiple skill sets -- comma-separated or repeated flag
java -cp migration-agent.jar:dw-helidon-skills.jar:acme-skills.jar \
     io.migration.agent.AgentRunner \
     --project-dir /path/to/project \
     --skills-package io.migration.skills.dw,com.acme.custom.skills

# Dry-run -- skills execute, changes are computed but nothing is written to disk
java -cp ... io.migration.agent.AgentRunner --project-dir /path/to/project \
     --skills-package io.migration.skills.dw --dry-run

# Relax after-constraint enforcement -- conflicts become warnings, not errors
# Useful for early-stage skill development; strict mode is the default
java -cp ... io.migration.agent.AgentRunner --project-dir /path/to/project \
     --skills-package io.migration.skills.dw --allow-order-conflicts
```

`SourceTypeRegistry` implementations are discovered from the same packages
using the same scan (see Section 11).

---

## 9. MigrationPlanner

Evaluates skill triggers against the inventory before execution. Produces a
`MigrationPlan` mapping each skill to its resolved target files. This plan is
presented to the user at the approval step.

```java
public class MigrationPlanner {

    public MigrationPlan plan(List<MigrationSkill> skills,
                               ProjectInventory inventory,
                               List<String> compileErrors) {

        SkillContext ctx = SkillContext.forPlanning(inventory, compileErrors);

        List<SkillExecutionEntry> entries = skills.stream()
            .filter(skill -> triggersMatch(skill, ctx))
            .map(skill -> new SkillExecutionEntry(
                skill,
                resolveTargetFiles(skill, inventory)))  // absolute outputDir paths
            .toList();   // already sorted by @Skill(order=...)

        return new MigrationPlan(entries);
    }

    private List<String> resolveTargetFiles(MigrationSkill skill,
                                             ProjectInventory inventory) {
        // Project-scope skills opt out of pre-resolution
        if (isProjectScope(skill)) return List.of();

        // File-scope: resolve matching files to absolute outputDir paths
        return matchingFiles(skill, inventory).stream()
            .map(f -> toAbsoluteOutputPath(f.sourceFile()))
            .toList();
    }
}

public record MigrationPlan(List<SkillExecutionEntry> entries) {
    public record SkillExecutionEntry(
        MigrationSkill skill,
        List<String> targetFiles,        // absolute outputDir paths; empty for project-scope
        SkillConfidence confidence,      // shown at ApprovePlan HITL step
        boolean aiInvolved               // true if skill type is AI-backed or has AI fallback
    ) {}
}

public enum SkillConfidence {
    HIGH,    // OpenRewrite recipe -- deterministic, fully reversible
    MEDIUM,  // AI-backed with postchecks -- bounded but non-deterministic
    LOW      // AI-backed with no postchecks, or project-scope with complex inventory queries
}
```

The planner is called twice: before skills run (compileErrors empty -- selects
inventory-triggered skills) and inside the compile-fix loop (compileErrors
populated -- additionally activates error-triggered skills with order >= 200).

**After-constraint validation:** Before producing the plan, the planner validates all
`@Skill(after=...)` declarations. If any listed predecessor skill would run after the
current skill under the resolved `order` values, the planner reports the conflict and
aborts with an actionable error. `after` constraints take precedence over `order`
tiebreaking and work across skill JARs that share numeric order values.

**Dynamic target expansion:** The plan produced here is used as a starting point, not
a complete specification. After each skill executes, the orchestrator checks its
`SkillResult` for `FileChange` entries where `before` is empty (generated files) or
where the file path changed (renames). If any are found, a `RefreshInventory` step
re-scans the affected paths in `outputDir`, and the planner is re-run for all skills
with `order > currentSkillOrder` against the refreshed inventory. Newly matched skills
receive correctly resolved `targetFiles()` from this re-run and are appended to the
execution queue. This prevents downstream skills from either missing generated files
or operating on stale target lists.

---

## 10. AI-Backed Skills

Used when the trigger is deterministic but the transformation is too complex or
varied for a deterministic recipe. The skill makes a focused, multi-turn LLM call
rather than a free-form agentic tool loop.

```java
@Skill(value = "Migrate complex provider implementations", order = 60)
@Triggers(@Trigger(annotationType = "javax.ws.rs.ext.Provider"))
@Postchecks(
    forbidImports = {"javax.ws.rs.ext.*"},
    requireTodos  = {"MIGRATION_TODO"},
    onFail        = PostcheckPolicy.INVOKE_FALLBACK
)
@Fallback(type = FallbackType.AI, promptRef = "provider-migration-fallback")
public class ProviderMigrationSkill implements MigrationSkill {

    @Override
    public SkillResult apply(SkillContext ctx) {
        String prompt   = PromptLoader.load("provider-migration");
        String examples = ExampleLoader.load("target-error-handling");

        // File-scope: ctx.targetFiles() contains absolute outputDir paths
        List<FileChange> changes = ctx.targetFiles().stream()
            .map(file -> transformWithRetry(ctx, file, prompt, examples))
            .toList();
        return SkillResult.of(changes);
    }

    private FileChange transformWithRetry(SkillContext ctx, String file, ...) {
        String current  = Files.readString(Path.of(file));
        String original = Files.readString(Path.of(ctx.workspace().sourceFileFor(file)));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(prompt + "\n\nExamples:\n" + examples));
        messages.add(UserMessage.from("Original:\n" + original + "\n\nCurrent:\n" + current));

        Set<String> seenOutputHashes = new HashSet<>();  // cycle detection

        for (int attempt = 0; attempt < 3; attempt++) {
            String result = ctx.model().chat(messages);

            // Cycle detection: if this output is identical to a prior attempt's output,
            // the model is looping (e.g. fix A introduces violation B, fix B reintroduces
            // violation A). Stop immediately rather than burning more tokens.
            String hash = Integer.toHexString(result.hashCode());
            if (!seenOutputHashes.add(hash))
                return FileChange.of(file, current,
                    TodoInserter.wrap(current, "MIGRATION_TODO: AI retry loop detected"));

            // Guard against runaway rewrites before running postchecks.
            // Fails fast if the output exceeds maxDeltaLines changed lines or
            // maxOutputBytes total size (both configurable via SkillConfig).
            AiRetryGuard.GuardResult guard = AiRetryGuard.check(current, result, config);
            if (guard.exceeded())
                return FileChange.of(file, current,
                    TodoInserter.wrap(current, "MIGRATION_TODO: AI output exceeded size guard"));

            // PostcheckRunner evaluates the @Postchecks annotation on this skill class:
            // forbidImports, forbidAnnotations, forbidPatterns, requireTodos rules are
            // each checked against the candidate output text. Violations are collected
            // into PostcheckResult.violations() for deterministic feedback.
            PostcheckResult check = PostcheckRunner.check(
                result, getClass().getAnnotation(Postchecks.class));
            if (SyntaxValidator.isValid(result) && check.passed())
                return FileChange.of(file, current, result);
            messages.add(AiMessage.from(result));
            messages.add(UserMessage.from(
                "Postchecks failed:\n" + check.violations() + "\nPlease fix."));
        }

        return FileChange.of(file, current,
            TodoInserter.wrap(current, "MIGRATION_TODO: requires manual review"));
    }
}
```

Prompts and target-framework examples live in the **skill project's** resources,
not the framework:

```
dw-helidon-skills/src/main/resources/
  prompts/             AI agent prompts specific to this migration
  examples/            target framework code snippets for few-shot context
```

| | Recipe / Feature | AI Agent (compile / test fix) |
|---|---|---|
| Activation | Inventory trigger -- known pattern | Compile errors -- reactive |
| Scope | One file at a time, focused prompt | Multiple files, open-ended |
| LLM interaction | Single call with combined prompt | Tool-calling agentic loop |
| Retries | Post-compile fix loop handles residuals | Progress-based loop until unstuck |

---

## 11. Compile Error Handling

### SourceTypeRegistry -- The Classification Source of Truth

Compile error classification (SKILL_GAP vs COMPILE_ONLY vs NOVEL) requires a
registry of FQN types that the skill set is responsible for. Without this, the
classifier cannot reliably distinguish a skill that missed a type from a type it
never knew about.

Each skill project provides a `SourceTypeRegistry` implementation:

```java
// Framework interface in migration-core
public interface SourceTypeRegistry {
    /**
     * Returns patterns that match the FQN type names this skill set is responsible
     * for migrating. Used by CompileErrorAnalyzer to classify compile errors.
     *
     * Pattern semantics: each pattern is a Java regex matched against a fully-qualified
     * type name (e.g. "io.dropwizard.auth.Auth") using String.matches(), which anchors
     * the pattern to the full string. Use "io\\.dropwizard\\..*" to match all types
     * in the io.dropwizard package tree.
     *
     * Note: these patterns match FQN type names, not import declaration lines.
     * "io\\.dropwizard\\..*" matches the type name "io.dropwizard.auth.Auth",
     * not the source line "import io.dropwizard.auth.Auth;".
     */
    Set<String> knownTypePatterns();
}
```

```java
// In dw-helidon-skills -- the source of truth for DW type coverage
public class DwSourceTypeRegistry implements SourceTypeRegistry {
    @Override
    public Set<String> knownTypePatterns() {
        return Set.of(
            "io\\.dropwizard\\..*",
            "com\\.codahale\\.metrics\\..*",
            "io\\.dropwizard\\.auth\\..*",
            "javax\\.ws\\.rs\\..*",
            "jakarta\\.ws\\.rs\\..*"
        );
    }
}
```

`SourceTypeRegistry` implementations are discovered from the same classpath scan
as skills (same package prefixes). Multiple registries from multiple skill JARs
are merged at runtime.

**Absence behavior:** If no `SourceTypeRegistry` implementation is found on the
classpath, `CompileErrorAnalyzer` logs a warning at startup and treats all unresolved
types as NOVEL -- classification is disabled and every compile error goes to the AI
agent. Using a skill project without a `SourceTypeRegistry` is valid but inefficient;
the tool will print a clear warning prompting the developer to add one.

**Registry validation:** `String.matches()` is fully anchored and easy to miswrite
(e.g. forgetting to escape dots, or writing a prefix without `.*`). The framework
provides `SourceTypeRegistryVerifier` in `migration-core-testkit` to catch mistakes
early. Skill projects are expected to include at least one test using it:

```java
class DwSourceTypeRegistryTest {
    @Test void patterns_are_valid_and_match_expected_types() {
        SourceTypeRegistryVerifier.of(new DwSourceTypeRegistry())
            .matches("io.dropwizard.Application")
            .matches("com.codahale.metrics.Counter")
            .doesNotMatch("io.helidon.Application")
            .doesNotMatch("io.dropwizard")  // package fragment, not a type name
            .verifyAllPatternsAreValidRegex();
    }
}
```

Without this test, pattern correctness degrades silently -- a missed escape or a
missing `.*` will silently misclassify errors at runtime.

### Classify Before Routing

```java
public record CompileError(
    String file,                        // absolute outputDir path
    int line,
    String message,                     // raw compiler message
    Optional<String> rawSymbol,         // raw symbol from compiler: may be simple name
                                        //   ("Foo") or qualified ("io.dropwizard.Foo")
    Optional<String> resolvedFqn        // FQN enriched from file imports (see below);
                                        //   used for classification, not rawSymbol
) {}
```

**FQN enrichment:** The compiler often reports simple names ("cannot find symbol: class
Foo"). Before classification, `CompileErrorParser` enriches `resolvedFqn` using a
three-step strategy in priority order:

1. **Symbol map (primary):** Look up the simple name in `JavaFileInfo.symbolMap` for the
   affected file. The symbol map is populated by `JavaProjectVisitor` using OpenRewrite's
   type resolution during inventory scanning -- it contains every type reference that
   OpenRewrite successfully resolved, keyed by simple name. This is the most reliable
   source because it reflects actual type attribution rather than heuristics. Shared
   simple names (e.g. `Response`, `Context`, `Logger`) that appear in multiple packages
   are unambiguous here because OpenRewrite resolves them using the full classpath.

2. **Explicit imports:** If the symbol map has no entry (the file had parse errors, or
   OpenRewrite couldn't resolve the type), fall back to import-list lookup. If the file
   has exactly one import ending in `.Foo`, that FQN is used. If the symbol is already
   qualified (e.g. from "package io.dropwizard does not exist"), the qualified form is
   used directly.

3. **Wildcard and static import fallback:** When steps 1 and 2 both fail (e.g.
   `import io.dropwizard.auth.*;` or `import static io.dropwizard.auth.Auth.*;`), the
   parser consults the `SourceTypeRegistry` reverse index. If the simple name uniquely
   matches the terminal component of exactly one known type pattern, that FQN is used.
   For static imports the import prefix is used as the package hint before consulting
   the reverse index.

If no step resolves the FQN unambiguously, `resolvedFqn` is empty and the error is
classified as NOVEL. This is a safe degradation: the AI agent receives the error with
`rawSymbol` and full file context, which is usually sufficient to fix it.

**Determinism and caching:** The reverse index is built once at startup from the merged
`SourceTypeRegistry` patterns and is immutable for the lifetime of the run. When the
reverse index lookup finds multiple candidate FQNs for the same simple name (ambiguous
match), the ambiguity is logged at DEBUG level listing all candidates, `resolvedFqn` is
left empty, and the error goes to NOVEL. This makes the degradation observable without
silently misclassifying errors.

```java
public class CompileErrorAnalyzer {

    private final Set<String> knownPatterns; // merged from all discovered registries
    private final MigrationLog migrationLog;

    public ErrorAnalysis analyze(List<CompileError> errors) {
        return new ErrorAnalysis(
            errors.stream()
                .collect(groupingBy(this::classify))
        );
    }

    private ErrorClass classify(CompileError error) {
        // resolvedFqn is always a FQN or empty -- never a raw simple name
        boolean knownType = error.resolvedFqn()
            .map(fqn -> knownPatterns.stream().anyMatch(fqn::matches))
            .orElse(false);

        if (!knownType)
            return ErrorClass.NOVEL;

        return migrationLog.skillTouched(error.file())
            ? ErrorClass.SKILL_GAP      // skill ran but missed this type
            : ErrorClass.COMPILE_ONLY;  // no skill ran -- not detectable upfront
    }
}
```

### MigrationLog -- Per-Skill, Per-File Intent

`migrationLog.skillTouched(file)` means the skill emitted a `FileChange` with a
**non-empty diff** for that file. Three explicit statuses are recorded:

```java
public record MigrationLogEntry(
    Class<? extends MigrationSkill> skill,  // which skill
    String file,                            // absolute outputDir path
    LogStatus status                        // progression: TARGETED -> ATTEMPTED -> CHANGED
) {}

public enum LogStatus {
    TARGETED,   // file was in targetFiles() -- apply() not yet called
    ATTEMPTED,  // apply() returned; FileChange emitted but diff may be empty
    CHANGED     // FileChange had a non-empty diff -- the only status counted as "touched"
}
```

The orchestrator records `TARGETED` when it resolves target files, upgrades to
`ATTEMPTED` after `apply()` returns, and upgrades to `CHANGED` only when the diff is
non-empty. A skill that targets a file but produces no change (no-op) is logged as
`ATTEMPTED`, not `CHANGED`. That file will be classified `COMPILE_ONLY`, not
`SKILL_GAP`, which is the correct outcome -- the skill did not intend to transform it.

### Handling by Class

**SKILL_GAP** -- a skill ran on this file and should have handled this type but
did not. Re-run the responsible skill with only the affected file as its target.
Save the error as a skill improvement candidate for `generate-skill`. When SKILL_GAP
errors are routed to the AI agent (after skill retry fails), the agent receives the
`FileChange` produced by the responsible skill -- both `before` and `after` content --
so it can determine whether the skill caused the regression (introduced a bad change)
or simply missed a spot. This diff context is retrieved from `MigrationLog` by file
and skill class. Skill gap errors are diagnostic signals that a skill needs improving.

**COMPILE_ONLY** -- a known type appeared in a file no skill touched. Not
detectable from source analysis alone. Handled by error-triggered skills with
`order >= 200` (e.g., missing transitive dependencies, classpath type resolution
failures). This set is intentionally small -- typically 2-5 skills.

**NOVEL** -- unrecognised type. Routed to the AI agent with rich context:
- Structured error data (type name, file, line -- not raw compiler output)
- Current file content (absolute outputDir path)
- Original source file (for intent -- what the code was doing before migration)
- Migration log entry (which skill transformed this file and what it intended)
- Full transitive dependency tree (`inventory.transitiveDependencies()`) -- allows the
  agent to distinguish classpath conflicts ("Jar Hell": version mismatch, duplicate
  classes across JARs) from genuine code errors without re-running Maven
- Relevant target-framework API example

### Progress-Based Exit, Not Iteration Count

```java
var compileFixLoop = AgenticServices.loopBuilder()
    .exitCondition(scope ->
        scope.readState("compilationSuccess", false) ||
        scope.readState("stuck", false))
    .build();

// stuck    = NOVEL error count not decreasing for 2 consecutive rounds
// regression = error count increases after a fix -> rollback last AI change
```

When stuck: HITL escalation presenting remaining error groups with file counts
and options -- continue, skip with TODOs, open for manual editing, or abort.

---

## 12. Skill Generation

OpenRewrite recipes require deep AST API knowledge. The `SkillGeneratorAgent`
reduces this barrier by generating a complete skill from a before/after Java file
pair:

```bash
# From explicit before/after files
migration-agent generate-skill \
  --before samples/before/SomeClass.java \
  --after   samples/after/SomeClass.java \
  --description "Migrate X pattern to Y"

# From AI fallback results captured in a previous migration report
migration-agent generate-skill --from-report migration-report.json
```

Output per example:
1. Annotated `MigrationSkill` class -- triggers inferred from the before file,
   postchecks inferred from the after file
2. OpenRewrite recipe implementing the AST transform
3. JUnit test with the before/after pair

The developer reviews, adds to their skill project, and releases. No centralized
server. Skills are code -- improved and shared through standard version control.

### The Improvement Loop

```
Migration run
  -> AI fallback handles unmatched files
  -> migration-report.json records before/after per file (local -- never leaves machine)
  -> Developer: migration-agent generate-skill --from-report migration-report.json
  -> Reviews generated classes, adds to skill project
  -> Other users benefit on next skill project release
```

---

## 13. Framework vs. Skill Project Responsibilities

### migration-core provides

- `MigrationSkill` interface + all annotations (`@Skill`, `@Trigger`, `@Postchecks`, `@Fallback`)
- `SkillFactory`, `SkillConfig` -- optional constructor injection; `SkillConfig` carries AI retry thresholds
- `Workspace` -- I/O path helpers (`outputDir`, `sourceDir`, `sourceFileFor`, `isDryRun`)
- `AiRetryGuard` -- runaway-rewrite guard for AI agent retry loops
- `ProjectInventory`, `JavaFileInfo`, `DependencyInfo`, `JavaProjectVisitor`
- `TriggerCondition`, `TriggerPredicates`
- `SourceTypeRegistry` interface
- `SkillDiscovery` -- classpath scanning with mandatory package prefixes
- `MigrationPlanner`, `MigrationPlan`, `ProjectState`, `MigrationLog`, `MigrationLogEntry`
- `CompileErrorAnalyzer`, `CompileErrorParser`, `CompileError`, `ErrorAnalysis`
- `PostcheckRunner`, `PostcheckResult` -- evaluates `@Postchecks` against candidate output
- Low-level `@Tool` classes for AI agents (read, write, compile, test, validate)
- `SyntaxValidator`, `PatternValidator`

### migration-core-testkit provides

- `SourceTypeRegistryVerifier` -- validates registry patterns compile and match expected types
- `SkillTestHarness` -- instantiates skills with injected test doubles via `SkillFactory`

### migration-agent provides

- `MigrationWorkflow` -- full LC4j SequenceAgent pipeline
- `FixCompileErrorsAgent`, `FixTestFailuresAgent`
- `SkillGeneratorAgent` + `generate-skill` CLI command
- `ModelFactory` -- configurable LLM provider (OCI GenAI default)
- `AgentRunner` -- CLI entry point

### Skill projects provide

- `MigrationSkill` implementations (`@Skill` + `apply()`)
- `SourceTypeRegistry` implementation -- FQN patterns of source-framework types covered
- `SourceTypeRegistryTest` using `SourceTypeRegistryVerifier` (required)
- OpenRewrite recipes used as skill backends
- `TriggerCondition` classes for complex trigger logic
- `SkillFactory` implementations for skills requiring constructor injection (optional)
- Prompt files and target-framework example snippets
- Before/after test pairs for each skill

---

## 14. Implementation Order

### Phase 1 -- Core Framework (migration-core)
1. `JavaFileInfo`, `DependencyInfo`, `ProjectInventory`
2. `JavaProjectVisitor` -- single generic OpenRewrite visitor with FQN type attribution
3. `MigrationSkill`, `SkillContext`, `SkillResult`, `FileChange`
4. All annotations: `@Skill`, `@Trigger`, `@Triggers`, `@Postchecks`, `@Fallback`
5. `TriggerCondition`, `TriggerPredicates`
6. `ProjectState`, `MigrationLog`
7. `SourceTypeRegistry` interface
8. `SkillDiscovery` -- ClassGraph-based, mandatory package prefixes
9. `MigrationPlanner`, `MigrationPlan`
10. `CompileErrorAnalyzer`, `CompileError`, `ErrorClass`, `ErrorAnalysis`
11. Low-level `@Tool` classes

### Phase 2 -- Agentic Workflow (migration-agent)
1. `MigrationWorkflow` -- SequenceAgent pipeline
2. `FixCompileErrorsAgent` -- rich context, progress-based loop, HITL escalation
3. `FixTestFailuresAgent`
4. `ModelFactory`, `AgentRunner`

### Phase 3 -- DW -> Helidon Skills (dw-helidon-skills)
Each skill with a unit test using before/after source pairs:

1. `DwSourceTypeRegistry` -- known DW/JAX-RS type patterns (order: implement first)
2. `DependencyMigrationSkill` (order=10)
3. `ImportMigrationSkill` (order=15)
4. `AnnotationMigrationSkill` (order=20)
5. `HealthCheckMigrationSkill` (order=30)
6. `MetricsMigrationSkill` (order=35)
7. `ClientMigrationSkill` (order=40)
8. `SecurityAuthSkill` -- AI-backed with fallback (order=50)
9. `ProviderMigrationSkill` -- AI-backed with fallback (order=55)
10. `ApplicationMigrationSkill` -- project-scope (order=90)
11. `ClasspathRepairSkill` -- error-triggered (order=200)
12. `MissingTransitiveDependencySkill` -- error-triggered (order=210)

### Phase 4 -- Skill Generator (migration-agent)
1. `SkillGeneratorAgent` -- from before/after pair or migration report
2. `generate-skill` CLI command

### Phase 5 -- Integration Test
End-to-end migration of a sample Dropwizard project using the Phase 3 skill set.
Verify: copy -> inventory -> plan -> approve -> skills -> review -> compile-fix ->
test-fix -> report.

---

## 15. Key Design Decisions

| Decision | Rationale |
|---|---|
| Framework contains no migration knowledge | Domain knowledge belongs in skill projects. Framework is a stable reusable host. |
| Skills in a separate Maven project | Independently versioned and released. Multiple skill sets can coexist on the classpath. |
| One annotated class per skill, no registration | Lowest possible barrier. Adding a skill requires no framework changes. |
| Order declared in @Skill(order=...) | Explicit, co-located with the skill, visible without reading a separate catalog. |
| @Skill(after=...) for explicit cross-skill dependencies | Prevents numeric order drift across JARs; planner enforces constraints and reports conflicts. |
| Mandatory package prefixes for discovery | Prevents accidental class loading from unrelated dependencies on the classpath. |
| Runtime classpath scanning (ClassGraph) | No META-INF/services files, no hand-maintained lists. Add a JAR, skills are found. |
| Inventory is neutral Java facts | New migration paths cost nothing to support in the framework. |
| FQN annotation matching | Simple names are ambiguous across frameworks. OpenRewrite provides FQN via type attribution. |
| File-scope: planner resolves targets; project-scope: inventory in apply() | Both patterns are valid and expected. The distinction is declared via @Skill(scope=...), not via a marker interface. |
| targetFiles() always contains absolute outputDir paths | Unambiguous contract. Skills always operate on the migrated copy, never the original. |
| SourceTypeRegistry per skill project | Explicit, extensible source of truth for compile error classification. Multiple registries merged at runtime. |
| SourceTypeRegistryVerifier test required | Fully-anchored regex mistakes are silent at runtime; a test catches them at build time. |
| MigrationLog TARGETED/ATTEMPTED/CHANGED | "Touched" means non-empty diff only. No-op targeted files are COMPILE_ONLY, not SKILL_GAP. |
| Dynamic target expansion via RefreshInventory + planner re-run | Skills generating new files do not cause downstream skills to miss them or receive stale targets. |
| PostcheckRunner evaluates @Postchecks on AI output | AI retry feedback is deterministic and violation-specific, not ad-hoc. |
| FQN enrichment: explicit imports first, registry reverse index fallback | Wildcard imports degrade gracefully to NOVEL rather than misclassifying. |
| symbolMap in JavaFileInfo (OpenRewrite type resolution) | Eliminates regex-based guessing for files that parsed cleanly; shared simple names are unambiguous. |
| CompileErrorParser reverse index built once, ambiguous drops logged | Classification is deterministic across runs; ambiguities are observable at DEBUG. |
| Shadow dependency tree (mvn dependency:tree in inventory) | AI agent can distinguish classpath conflicts from code errors without re-running Maven. |
| MigrationPlan.SkillConfidence + aiInvolved | ApprovePlan HITL step shows users what is deterministic vs AI-driven before execution. |
| SKILL_GAP AI context includes responsible skill's diff | Agent can determine whether the skill caused a regression or missed a spot. |
| AI retry cycle detection (hash-based) | Stops token burn when model is oscillating between two invalid states. |
| executionHistory(file) on SkillContext | Skills can see what prior skills did to the same file without coupling to each other. |
| --dry-run generates git-format patch | Developers review proposed migration with standard IDE diff / git apply without committing. |
| SkillFactory for constructor injection | Enables test doubles and configurable dependencies without static singletons or no-arg coupling. |
| SkillConfig.maxDeltaLines / maxOutputBytes + AiRetryGuard | Prevents runaway AI rewrites from propagating; fails fast with a TODO marker. |
| Workspace groups I/O helpers on SkillContext | Separates path/file concerns from facts/state/model; exposes isDryRun() in one place. |
| --dry-run mode (isDryRun()) | Skills execute fully; changes are computed but not written. Safe for CI validation and new skill sets. |
| --allow-order-conflicts downgrades after violations to warnings | Strict default; escape hatch for demos and early-stage skill development. |
| ProjectState for inter-skill data | Project-scope skills share discoveries without coupling to each other. |
| AI skills use focused multi-turn call | Self-correcting, bounded, predictable -- not a free-form agentic loop. |
| Compile error classification | Skill gaps -> improve the skill. Compile-only -> small error-triggered set. Novel -> AI. |
| Progress-based loop exit | Measures actual improvement rather than an arbitrary attempt count. |
| HITL escalation when stuck | Developer gets context and actionable options -- not a silent failure at iteration N. |
| No centralized database | Self-contained. Improvements shared through version control like any library. |

---

## 16. Known Constraints and Limitations

### Multi-Module Maven Projects

The framework models module topology via `ModuleInfo` and runs inventory scanning,
compile, and test in topological order. However, complex parent/child POM patterns
(e.g. BOM imports, version ranges, property interpolation across modules) rely on
Maven's effective-POM resolution rather than custom logic. Projects that use
non-standard aggregation patterns (e.g. nested multi-module trees, imported BOMs
from external repositories) may require the user to run `mvn help:effective-pom`
and review the merged dependency list before migration.

**Cross-module binary compatibility:** A skill that modifies a parent module's
public API (e.g. changes a method signature or removes a type) may break child
modules at the binary level without producing a source-level compile error in the
parent module itself. This will appear as NOVEL or SKILL_GAP errors in child module
compilation. Skills should prefer source-compatible API migrations (additive changes,
deprecation rather than removal) and should be reviewed for binary-compatibility
impact when they touch shared library modules. The compile-fix loop will surface
these errors, but the root cause may be a skill change in a different module.

### Build Profiles

All Maven invocations (generate-sources, compile, test) receive the `--active-profiles`
argument as specified by the user. The framework does not auto-detect profiles.
**Inventory and compile-error classification depend entirely on which profiles are
active.** If the project uses profiles to swap dependencies or source sets, the user
must identify and activate the correct profile set for the target migration path before
running the tool. Migrating with the wrong profile produces misleading inventory,
spurious compile errors, and incorrect classification.

### Annotation Processors and Generated Sources

The `GenerateSources` step runs `mvn generate-sources` before scanning, which
materialises annotation-processor output (Lombok, MapStruct, Dagger, JAXB, etc.)
into the outputDir. The inventory scanner marks files under standard generated-source
directories (e.g. `target/generated-sources`) as annotation-processor output and does
not include them in `JavaFileInfo.files()`. Compile errors caused by types that exist
only in generated output will therefore be classified NOVEL and routed to the AI agent.
This is correct behaviour -- generated types are not migration targets -- but skill
authors should be aware that fixing such errors usually means adding the right
annotation-processor dependency, not transforming source code.

### Source Language Scope

The framework targets Java source files. Kotlin, Scala, and Groovy are not supported:
`JavaProjectVisitor` uses the OpenRewrite Java parser and will skip non-Java sources.
Mixed-language projects can still be migrated for their Java portions; the developer
is responsible for non-Java source files. Lombok-heavy projects are handled correctly
provided `GenerateSources` runs first (see above).

### Custom Build Logic Outside the Maven Lifecycle

`CompileProjectTool` invokes `mvn compile`, which runs the full standard lifecycle up
to and including annotation processing and code generation. Custom steps registered
as Maven plugins that execute during `generate-sources` or `process-classes` are
included automatically. Steps outside the Maven lifecycle (e.g. shell scripts, Gradle
tasks, external code generators invoked manually) are not modelled. Projects with such
requirements need a manual pre-migration step before running the tool.

### Resource and Configuration File Migration

`FileChange` accepts any file path and string content, so skills *can* write non-Java
files (YAML, properties, XML, Dockerfiles, Helm charts). However, the framework
provides no resource inventory, no resource-aware triggers, and no resource-specific
transformation support. Config file migration must be implemented manually by skill
authors as project-scope skills that read and write resource files directly. This is a
known gap; resource inventory support is deferred to a future iteration.

### Runtime Correctness

Successful compilation and passing tests are the framework's correctness signals. The
tool does not verify runtime API contract compatibility, serialisation compatibility,
or behavioural equivalence beyond what the project's existing test suite covers. This
is an explicit Non-Goal: replacing developer validation or test coverage is outside
the framework's scope. Skill authors are encouraged to include integration tests in
before/after examples, and users should run the migrated project against realistic
workloads before promoting to production.
