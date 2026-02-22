package io.transmute.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.observability.AgentMonitor;
import io.transmute.agent.MigrationConfig;
import io.transmute.agent.ModelFactory;
import io.transmute.agent.WorkflowKeys;
import io.transmute.agent.agent.AgentToolsConfig;
import io.transmute.agent.agent.FixCompileErrorsAgent;
import io.transmute.agent.agent.FixTestFailuresAgent;
import io.transmute.catalog.*;
import io.transmute.compile.CompileErrorParser;
import io.transmute.inventory.ProjectInventory;
import io.transmute.skill.*;
import io.transmute.skill.annotation.Postchecks;
import io.transmute.skill.annotation.Skill;
import io.transmute.skill.annotation.SkillScope;
import io.transmute.skill.postcheck.PostcheckRunner;
import io.transmute.tool.CompileProjectTool;
import io.transmute.tool.CopyProjectTool;
import io.transmute.tool.RunTestsTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Assembles and runs the generic Transmute migration pipeline.
 *
 * <p>Pipeline (10 steps):
 * <ol>
 *   <li>Copy project to output dir</li>
 *   <li>Scan inventory (JavaProjectVisitor)</li>
 *   <li>Run SkillDiscovery</li>
 *   <li>Build migration plan (MigrationPlanner)</li>
 *   <li>Human approval (if !autoApprove)</li>
 *   <li>Execute skills in plan order</li>
 *   <li>Human review gate</li>
 *   <li>Compile-fix loop (max 5 iterations)</li>
 *   <li>Test-fix loop (max 5 iterations)</li>
 *   <li>Generate report</li>
 * </ol>
 */
public class MigrationWorkflow {

    private static final int MAX_COMPILE_FIX_ITERATIONS = 5;
    private static final int MAX_TEST_FIX_ITERATIONS = 5;
    private static final ProgressTicker PROGRESS_TICKER = new ProgressTicker();

    private final MigrationConfig config;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // Shared state populated during execution
    private ProjectInventory inventory;
    private List<MigrationSkill> skills;
    private List<SourceTypeRegistry> registries;
    private MigrationPlan plan;
    private MigrationLog migrationLog;
    private ProjectState projectState;

    public MigrationWorkflow(MigrationConfig config) {
        this.config = config;
    }

    public void run() throws Exception {
        System.out.println("=== Transmute Migration ===\n");

        System.setProperty("transmute.aiSummary", String.valueOf(config.verbose()));

        migrationLog = new MigrationLog();
        projectState = new ProjectState();

        var steps = new ArrayList<Object>();
        steps.addAll(buildSetupSteps());
        steps.addAll(buildSkillExecutionSteps());
        steps.addAll(buildReviewGate());
        steps.addAll(buildCompileFixLoop());
        steps.addAll(buildTestFixLoop());
        steps.addAll(buildReportStep());

        var monitor = new AgentMonitor();

        UntypedAgent pipeline = AgenticServices.sequenceBuilder()
                .name("TransmutePipeline")
                .description("Generic Transmute migration pipeline")
                .subAgents(steps.toArray())
                .outputKey(WorkflowKeys.REPORT)
                .errorHandler(errorContext -> {
                    PROGRESS_TICKER.stop();
                    System.err.println("Agent " + errorContext.agentName()
                            + " failed: " + errorContext.exception().getMessage());
                    return ErrorRecoveryResult.throwException();
                })
                .listener(monitor)
                .build();

        pipeline.invoke(Map.of(
                WorkflowKeys.SOURCE_DIR, config.projectDir(),
                WorkflowKeys.OUTPUT_DIR, config.outputDir()
        ));

        System.out.println("\n=== Migration Complete ===");
        System.out.println("Output: " + config.outputDir());
        System.out.println("\nAgent execution summary:");
        System.out.println("  Successful: " + monitor.successfulExecutions().size());
        System.out.println("  Failed:     " + monitor.failedExecutions().size());
    }

    // ── Phase builders ───────────────────────────────────────────────────────

    private List<Object> buildSetupSteps() {
        var copyProject = AgenticServices.agentAction(scope -> {
            System.out.println("[1/10] Copying project to output directory...");
            var result = new CopyProjectTool().copyProjectResult(
                    scope.readState(WorkflowKeys.SOURCE_DIR, ""),
                    scope.readState(WorkflowKeys.OUTPUT_DIR, ""));
            System.out.println("  " + result.message());
            if (!result.success()) {
                throw new RuntimeException("Copy failed: " + result.message());
            }
        });

        var scanInventory = AgenticServices.agentAction(scope -> {
            System.out.println("[2/10] Scanning project inventory...");
            inventory = scanProject(scope.readState(WorkflowKeys.SOURCE_DIR, ""));
            scope.writeState(WorkflowKeys.INVENTORY, inventory);
            System.out.println("  Scanned " + inventory.getJavaFiles().size() + " Java files.");
        });

        var discoverSkills = AgenticServices.agentAction(scope -> {
            System.out.println("[3/10] Discovering skills...");
            var discovery = new SkillDiscovery().discover(config.skillsPackages());
            skills = discovery.skills();
            registries = discovery.registries();
            System.out.println("  Found " + skills.size() + " skills, "
                    + registries.size() + " source type registries.");
        });

        var buildPlan = AgenticServices.agentAction(scope -> {
            System.out.println("[4/10] Building migration plan...");
            var planner = new MigrationPlanner(config.allowOrderConflicts());
            plan = planner.plan(skills, inventory, List.of());
            scope.writeState(WorkflowKeys.SKILLS_PLAN, plan);

            System.out.println("  Skills in plan: " + plan.entries().size());
            for (var entry : plan.entries()) {
                var skillAnn = entry.skill().getClass().getAnnotation(Skill.class);
                var skillScope = skillAnn != null ? skillAnn.scope() : SkillScope.FILE;
                var line = new StringBuilder("    - ")
                        .append(entry.skill().name())
                        .append(" [").append(entry.confidence()).append("]")
                        .append(" scope=").append(skillScope);
                if (skillScope == SkillScope.FILE) {
                    line.append(" files=").append(entry.targetFiles().size());
                }
                System.out.println(line);
                if (skillScope == SkillScope.FILE && !entry.targetFiles().isEmpty()) {
                    int shown = Math.min(5, entry.targetFiles().size());
                    for (int i = 0; i < shown; i++) {
                        System.out.println("        "
                                + Path.of(entry.targetFiles().get(i)).getFileName());
                    }
                    if (entry.targetFiles().size() > shown) {
                        System.out.println("        ... (" + (entry.targetFiles().size() - shown) + " more)");
                    }
                }
            }
        });

        var approvePlan = config.autoApprove()
                ? AgenticServices.agentAction(() -> System.out.println("[5/10] Plan approved (auto-approve)."))
                : AgenticServices.humanInTheLoopBuilder()
                        .description("Review the migration plan before executing skills")
                        .inputKey(WorkflowKeys.SKILLS_PLAN)
                        .outputKey(WorkflowKeys.PLAN_APPROVAL)
                        .requestWriter(p -> System.out.println("\n[5/10] Review migration plan above."))
                        .responseReader(() -> readUserInput("Proceed with migration? (yes/no): "))
                        .build();

        var checkApproval = AgenticServices.agentAction(scope -> {
            if (!config.autoApprove()) {
                String approval = scope.readState(WorkflowKeys.PLAN_APPROVAL, "");
                if (approval == null || !approval.trim().toLowerCase().startsWith("y")) {
                    throw new RuntimeException("Migration aborted by user.");
                }
            }
        });

        return List.of(copyProject, scanInventory, discoverSkills, buildPlan, approvePlan, checkApproval);
    }

    private List<Object> buildSkillExecutionSteps() {
        var executeSkills = AgenticServices.agentAction(scope -> {
            System.out.println("[6/10] Executing skills...");
            if (plan == null || plan.entries().isEmpty()) {
                System.out.println("  No skills to execute.");
                return;
            }

            var workspace = new Workspace(
                    scope.readState(WorkflowKeys.SOURCE_DIR, ""),
                    scope.readState(WorkflowKeys.OUTPUT_DIR, ""),
                    config.dryRun());
            var model = ModelFactory.create();
            var postcheckRunner = new PostcheckRunner();

            for (var entry : plan.entries()) {
                var skill = entry.skill();
                var ann = skill.getClass().getAnnotation(Skill.class);
                var skillScope = ann != null ? ann.scope() : SkillScope.FILE;

                System.out.println("  Running: " + skill.name());

                if (skillScope == SkillScope.FILE) {
                    for (var file : entry.targetFiles()) {
                        migrationLog.recordTargeted(skill.getClass(), file);
                        try {
                            var ctx = new SkillContext(
                                    inventory, List.of(file), projectState,
                                    List.of(), model, workspace, migrationLog);
                            migrationLog.recordAttempted(skill.getClass(), file);
                            var result = skill.apply(ctx);
                            if (result.success()) {
                                for (var change : result.changes()) {
                                    if (change.isChanged()) {
                                        migrationLog.recordChanged(skill.getClass(), file);
                                        if (!config.dryRun()) {
                                            Files.writeString(
                                                    workspace.outputPath(
                                                            Path.of(workspace.sourceDir())
                                                                    .relativize(Path.of(file)).toString()),
                                                    change.after());
                                        }
                                    }
                                }
                                // Postchecks
                                var failures = postcheckRunner.run(skill, result);
                                if (!failures.isEmpty()) {
                                    System.out.println("    Postcheck failures for " + file + ":");
                                    failures.forEach(f -> System.out.println("      " + f));
                                    tryFallback(skill, ctx, workspace, postcheckRunner, model);
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("    Skill " + skill.name()
                                    + " failed on " + file + ": " + e.getMessage());
                        }
                    }
                } else {
                    // PROJECT scope
                    try {
                        var ctx = new SkillContext(
                                inventory, List.of(), projectState,
                                List.of(), model, workspace, migrationLog);
                        var result = skill.apply(ctx);
                        if (!result.success()) {
                            System.err.println("    Skill " + skill.name()
                                    + " reported failure: " + result.message());
                        }
                    } catch (Exception e) {
                        System.err.println("    Skill " + skill.name()
                                + " failed: " + e.getMessage());
                    }
                }
            }
            System.out.println("  Skill execution complete.");
        });

        return List.of(executeSkills);
    }

    private void tryFallback(
            MigrationSkill skill,
            SkillContext ctx,
            Workspace workspace,
            PostcheckRunner postcheckRunner,
            dev.langchain4j.model.chat.ChatModel model) {
        var fallbackAnn = skill.getClass().getAnnotation(io.transmute.skill.annotation.Fallback.class);
        if (fallbackAnn == null) {
            return;
        }
        try {
            var fallbackClass = fallbackAnn.value();
            var fallback = fallbackClass.getDeclaredConstructor().newInstance();
            System.out.println("    Invoking fallback: " + fallback.name());
            var fbResult = fallback.apply(ctx);
            if (!fbResult.success()) {
                System.err.println("    Fallback also failed: " + fbResult.message());
            }
        } catch (Exception e) {
            System.err.println("    Fallback invocation failed: " + e.getMessage());
        }
    }

    private List<Object> buildReviewGate() {
        if (config.autoApprove()) {
            return List.of();
        }
        var reviewChanges = AgenticServices.humanInTheLoopBuilder()
                .description("Review skill-generated changes before compile/test")
                .inputKey(WorkflowKeys.SKILLS_PLAN)
                .outputKey(WorkflowKeys.HUMAN_APPROVAL)
                .requestWriter(result -> {
                    System.out.println("\n[7/10] Review changes in: " + config.outputDir());
                })
                .responseReader(() ->
                        readUserInput("\nApprove and proceed to compile+test? (yes/no): "))
                .build();

        var checkReviewApproval = AgenticServices.agentAction(scope -> {
            String approval = scope.readState(WorkflowKeys.HUMAN_APPROVAL, "");
            if (approval == null || !approval.trim().toLowerCase().startsWith("y")) {
                throw new RuntimeException("Migration stopped at review step.");
            }
        });

        return List.of(reviewChanges, checkReviewApproval);
    }

    private List<Object> buildCompileFixLoop() {
        var announce = AgenticServices.agentAction(() ->
                System.out.println("\n[8/10] Compile-fix loop (max "
                        + MAX_COMPILE_FIX_ITERATIONS + " iterations)..."));
        var compileStep = AgenticServices.agentAction(scope -> {
            System.out.println("  Compiling...");
            var result = new CompileProjectTool(config.activeProfiles())
                    .runCompile(scope.readState(WorkflowKeys.OUTPUT_DIR, ""));
            scope.writeState(WorkflowKeys.COMPILATION_SUCCESS, result.success());
            scope.writeState(WorkflowKeys.COMPILE_ERRORS, result.errors());
            if (result.success()) {
                System.out.println("  Compilation successful!");
            } else {
                System.out.println("  Compilation failed.");
                printFirstLines(result.errors(), 30);
            }
        });

        var summarize = AgenticServices.agentAction(scope -> {
            int iter = scope.readState(loopIterKey("Compile"), 0);
            boolean success = scope.readState(WorkflowKeys.COMPILATION_SUCCESS, false);
            String errors = scope.readState(WorkflowKeys.COMPILE_ERRORS, "");
            int errorCount = 0;
            int fileCount = 0;
            String firstError = "";
            if (!success && errors != null && !errors.isBlank() && inventory != null) {
                var parsed = new CompileErrorParser(inventory).parse(errors);
                errorCount = parsed.size();
                fileCount = (int) parsed.stream()
                        .map(io.transmute.compile.CompileError::file)
                        .distinct()
                        .count();
                if (!parsed.isEmpty()) {
                    firstError = parsed.get(0).message();
                }
            }
            System.out.println("  [Compile] Iteration " + iter + "/"
                    + MAX_COMPILE_FIX_ITERATIONS + " summary: "
                    + (success ? "SUCCESS" : "FAIL")
                    + (success ? "" : " errors=" + errorCount + " files=" + fileCount));
            if (!success && firstError != null && !firstError.isBlank()) {
                System.out.println("    First error: " + firstError);
            }
        });

        AgentToolsConfig.outputDir = config.outputDir();
        AgentToolsConfig.activeProfiles = config.activeProfiles();
        return buildFixLoop("Compile", announce, compileStep, summarize,
                FixCompileErrorsAgent.class, WorkflowKeys.COMPILATION_SUCCESS,
                WorkflowKeys.COMPILE_ERRORS, MAX_COMPILE_FIX_ITERATIONS);
    }

    private List<Object> buildTestFixLoop() {
        var announce = AgenticServices.agentAction(() ->
                System.out.println("\n[9/10] Test-fix loop (max "
                        + MAX_TEST_FIX_ITERATIONS + " iterations)..."));
        var testStep = AgenticServices.agentAction(scope -> {
            System.out.println("  Running tests...");
            var result = new RunTestsTool(config.activeProfiles())
                    .runMvnTest(scope.readState(WorkflowKeys.OUTPUT_DIR, ""));
            scope.writeState(WorkflowKeys.ALL_TESTS_PASS, result.success());
            scope.writeState(WorkflowKeys.TEST_OUTPUT, result.output());
            System.out.println(result.success()
                    ? "  All tests passed!" : "  Tests failed.");
        });

        var summarize = AgenticServices.agentAction(scope -> {
            int iter = scope.readState(loopIterKey("Test"), 0);
            boolean success = scope.readState(WorkflowKeys.ALL_TESTS_PASS, false);
            String output = scope.readState(WorkflowKeys.TEST_OUTPUT, "");
            int lineCount = (output == null || output.isBlank())
                    ? 0
                    : output.split("\\R").length;
            System.out.println("  [Test] Iteration " + iter + "/"
                    + MAX_TEST_FIX_ITERATIONS + " summary: "
                    + (success ? "SUCCESS" : "FAIL")
                    + " outputLines=" + lineCount);
        });

        // AgentToolsConfig already set by buildCompileFixLoop; no need to re-set
        return buildFixLoop("Test", announce, testStep, summarize,
                FixTestFailuresAgent.class, WorkflowKeys.ALL_TESTS_PASS,
                WorkflowKeys.TEST_OUTPUT, MAX_TEST_FIX_ITERATIONS);
    }

    private List<Object> buildReportStep() {
        var generateReport = AgenticServices.agentAction(scope -> {
            System.out.println("\n[10/10] Generating migration report...");
            try {
                var report = new LinkedHashMap<String, Object>();
                String outputDir = scope.readState(WorkflowKeys.OUTPUT_DIR, "");
                report.put("sourceDir", scope.readState(WorkflowKeys.SOURCE_DIR, ""));
                report.put("outputDir", outputDir);
                report.put("compilationSuccess",
                        scope.readState(WorkflowKeys.COMPILATION_SUCCESS, false));
                report.put("allTestsPass",
                        scope.readState(WorkflowKeys.ALL_TESTS_PASS, false));
                report.put("skillsExecuted",
                        migrationLog.allEntries().stream()
                                .filter(e -> e.status() == io.transmute.catalog.LogStatus.CHANGED)
                                .count());
                report.put("dryRun", config.dryRun());

                var reportPath = Path.of(outputDir, "migration-report.json");
                if (!config.dryRun()) {
                    json.writeValue(reportPath.toFile(), report);
                    System.out.println("  Report written to: " + reportPath);
                } else {
                    System.out.println("  [dry-run] Would write report to: " + reportPath);
                }
                scope.writeState(WorkflowKeys.REPORT, json.writeValueAsString(report));
            } catch (Exception e) {
                System.err.println("  Error generating report: " + e.getMessage());
            }
        });

        return List.of(generateReport);
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private <T> List<Object> buildFixLoop(
            String label,
            Object announceStep,
            Object runStep,
            Object perIterationSummary,
            Class<T> fixAgentClass,
            String successKey,
            String errorKey,
            int maxIter) {
        String iterKey = loopIterKey(label);
        var bumpIter = AgenticServices.agentAction(scope -> {
            int iter = scope.readState(iterKey, 0);
            scope.writeState(iterKey, iter + 1);
        });
        var fixAgent = AgenticServices.agentBuilder(fixAgentClass).build();
        var conditional = AgenticServices.conditionalBuilder()
                .subAgents(scope -> !(Boolean) scope.readState(successKey, false), fixAgent)
                .build();
        var loop = AgenticServices.loopBuilder()
                .name(label + "FixLoop")
                .subAgents(bumpIter, runStep, conditional, perIterationSummary)
                .maxIterations(maxIter)
                .exitCondition(scope -> (Boolean) scope.readState(successKey, false))
                .testExitAtLoopEnd(true)
                .build();
        var check = AgenticServices.agentAction(scope -> {
            if (!(Boolean) scope.readState(successKey, false)) {
                System.out.println("\n  [!] " + label + " fix loop exhausted after "
                        + maxIter + " iterations without success.");
                String lastErrors = scope.readState(errorKey, "");
                if (lastErrors != null && !lastErrors.isBlank()) {
                    System.out.println("  Persisting error:");
                    printFirstLines(lastErrors, 15);
                }
                System.out.println("  Review the output directory manually: "
                        + scope.readState(WorkflowKeys.OUTPUT_DIR, ""));
                throw new RuntimeException(label + " failed after " + maxIter + " attempts.");
            }
        });
        return List.of(announceStep, loop, check);
    }

    private String loopIterKey(String label) {
        return label.toLowerCase(Locale.ROOT) + "Iteration";
    }

    private ProjectInventory scanProject(String sourceDir) {
        // Wire OpenRewrite recipe execution
        var visitor = new io.transmute.inventory.JavaProjectVisitor();
        var inv = visitor.getInitialValue();
        inv.setRootDir(sourceDir);
        inv.setProject(Path.of(sourceDir).getFileName().toString());

        // Walk the source tree and run the visitor via OpenRewrite
        try {
            var parser = org.openrewrite.java.JavaParser.fromJavaVersion()
                    .logCompilationWarningsAndErrors(false)
                    .build();

            var sourceRoot = Path.of(sourceDir);
            try (var walk = Files.walk(sourceRoot)) {
                var javaFiles = walk
                        .filter(p -> p.toString().endsWith(".java"))
                        .toList();

                if (!javaFiles.isEmpty()) {
                    var ctx = new org.openrewrite.InMemoryExecutionContext(
                            e -> inv.addError(e.getMessage()));
                    var sources = parser.parse(javaFiles, sourceRoot, ctx).toList();
                    for (var source : sources) {
                        visitor.visit(source, inv);
                    }
                }
            }
        } catch (Exception e) {
            inv.addWarning("Inventory scan error: " + e.getMessage());
        }
        return inv;
    }

    private String readUserInput(String prompt) {
        System.out.print(prompt);
        var console = System.console();
        if (console != null) {
            return console.readLine();
        }
        try {
            var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in));
            return reader.readLine();
        } catch (Exception e) {
            return "no";
        }
    }

    private void printFirstLines(String text, int limit) {
        if (text == null || text.isBlank()) {
            return;
        }
        var lines = text.split("\\R");
        int count = Math.min(limit, lines.length);
        for (int i = 0; i < count; i++) {
            System.out.println("  " + lines[i]);
        }
        if (lines.length > limit) {
            System.out.println("  ... (" + (lines.length - limit) + " more lines)");
        }
    }

    // ── Progress ticker ──────────────────────────────────────────────────────

    private static final class ProgressTicker {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private ScheduledExecutorService executor;
        private Instant startTime;

        void start(String label, Duration interval) {
            stop();
            this.startTime = Instant.now();
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "progress-ticker");
                t.setDaemon(true);
                return t;
            });
            running.set(true);
            long seconds = Math.max(5, interval.toSeconds());
            executor.scheduleAtFixedRate(() -> {
                if (!running.get()) return;
                long elapsed = Duration.between(startTime, Instant.now()).toSeconds();
                System.out.println("  [" + label + "] still running (" + elapsed + "s elapsed)...");
            }, seconds, seconds, TimeUnit.SECONDS);
        }

        void stop() {
            running.set(false);
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }
}
