package io.transmute.agent.workflow;

import dev.langchain4j.service.AiServices;
import io.transmute.agent.ModelFactory;
import io.transmute.agent.TransmuteConfig;
import io.transmute.agent.agent.CommentOutBrokenCodeAgent;
import io.transmute.agent.agent.FixCompileErrorsAgent;
import io.transmute.agent.agent.FixTestFailuresAgent;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.tool.MavenBuildTool;
import io.transmute.tool.MigrationTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Steps 10–11: compile-fix / test-fix loops.
 */
class RepairLoopService {

    private static final int MAX_FIX_ITERATIONS = 5;
    private static final String JOURNAL_FILE = "migration-journal.md";

    private final TransmuteConfig config;
    private final String projectSummary;
    private final String catalogHints;
    private final MarkdownMigrationLoader.Hints hints;

    RepairLoopService(TransmuteConfig config, String projectSummary, String catalogHints,
                      MarkdownMigrationLoader.Hints hints) {
        this.config = config;
        this.projectSummary = projectSummary;
        this.catalogHints = catalogHints;
        this.hints = hints;
    }

    RepairResult repair(MigrationExecutor.ExecutionResult execResult,
                        int compileStepNum, int testStepNum, int totalSteps) {
        boolean compileSuccess = false;
        boolean compileDegraded = false;
        int compileIterations = 0;
        boolean testSuccess = false;
        int testIterations = 0;

        // ── Compile-fix loop ──────────────────────────────────────────────────
        Out.step(compileStepNum, totalSteps, "Compile-fix loop  (max " + MAX_FIX_ITERATIONS + " attempts)");
        for (int i = 1; i <= MAX_FIX_ITERATIONS; i++) {
            System.out.println("  " + Out.DIM + "Compiling… [" + i + "/" + MAX_FIX_ITERATIONS + "]" + Out.RESET);
            var result = new MavenBuildTool(config.activeProfiles()).runCompile(config.outputDir());
            if (result.success()) {
                compileSuccess = true;
                compileIterations = i;
                Out.ok("Compilation successful");
                Out.rule();
                break;
            }
            Out.error("Compilation failed (attempt " + i + "/" + MAX_FIX_ITERATIONS + ")");
            printFirstLines(result.output(), 30);
            if (i == MAX_FIX_ITERATIONS) {
                Out.warn("Max iterations reached — invoking comment-out agent…");
                var effectiveHints = Stream.of(catalogHints, hints.compileHints())
                        .filter(s -> s != null && !s.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
                buildCommentOutAgent().fix(config.outputDir(), result.output(), readJournal(), effectiveHints);
                compileIterations = MAX_FIX_ITERATIONS;
                compileDegraded = true;
                Out.warn("Compilation may still have errors — broken constructs were commented out");
                Out.rule();
                break;
            }
            System.out.println("  " + Out.YELLOW + "→ Invoking compile-fix agent…" + Out.RESET);
            var effectiveCompileHints = Stream.of(catalogHints, hints.compileHints())
                    .filter(s -> s != null && !s.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
            buildCompileFixAgent().fix(config.outputDir(), result.output(), projectSummary, readJournal(), effectiveCompileHints);
        }
        if (compileIterations == 0) {
            Out.rule();
        }

        // ── Test-fix loop ─────────────────────────────────────────────────────
        Out.step(testStepNum, totalSteps, "Test-fix loop  (max " + MAX_FIX_ITERATIONS + " attempts)");
        for (int i = 1; i <= MAX_FIX_ITERATIONS; i++) {
            System.out.println("  " + Out.DIM + "Running tests… [" + i + "/" + MAX_FIX_ITERATIONS + "]" + Out.RESET);
            var result = new MavenBuildTool(config.activeProfiles()).runMvnTest(config.outputDir());
            if (result.success()) {
                testSuccess = true;
                testIterations = i;
                Out.ok("All tests passed");
                Out.rule();
                break;
            }
            Out.error("Tests failed (attempt " + i + "/" + MAX_FIX_ITERATIONS + ")");
            printFirstLines(result.output(), 30);
            if (i == MAX_FIX_ITERATIONS) {
                testIterations = MAX_FIX_ITERATIONS;
                throw new RuntimeException("Tests failed after " + MAX_FIX_ITERATIONS + " attempts.");
            }
            System.out.println("  " + Out.YELLOW + "→ Invoking test-fix agent…" + Out.RESET);
            var effectiveTestHints = Stream.of(catalogHints, hints.testHints())
                    .filter(s -> s != null && !s.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
            buildTestFixAgent().fix(config.outputDir(), result.output(), projectSummary, readJournal(), effectiveTestHints);
        }
        if (testIterations == 0) {
            Out.rule();
        }

        return new RepairResult(compileSuccess, compileDegraded, compileIterations,
                testSuccess, testIterations);
    }

    // ── Result record ─────────────────────────────────────────────────────────

    record RepairResult(
            boolean compileSuccess, boolean compileDegraded, int compileIterations,
            boolean testSuccess, int testIterations) {}

    // ── Private helpers ───────────────────────────────────────────────────────

    private FixCompileErrorsAgent buildCompileFixAgent() {
        return AiServices.builder(FixCompileErrorsAgent.class)
                .chatModel(ModelFactory.create())
                .tools(new MigrationTools(config.outputDir(), config.activeProfiles()).codeEditTools())
                .build();
    }

    private FixTestFailuresAgent buildTestFixAgent() {
        return AiServices.builder(FixTestFailuresAgent.class)
                .chatModel(ModelFactory.create())
                .tools(new MigrationTools(config.outputDir(), config.activeProfiles()).codeEditTools())
                .build();
    }

    private CommentOutBrokenCodeAgent buildCommentOutAgent() {
        return AiServices.builder(CommentOutBrokenCodeAgent.class)
                .chatModel(ModelFactory.create())
                .tools(new MigrationTools(config.outputDir(), config.activeProfiles()).codeEditTools())
                .build();
    }

    private void printFirstLines(String text, int limit) {
        if (text == null || text.isBlank()) return;
        var lines = text.split("\\R");
        int count = Math.min(limit, lines.length);
        for (int i = 0; i < count; i++) {
            System.out.println(Out.DIM + "    " + lines[i] + Out.RESET);
        }
        if (lines.length > limit) {
            System.out.println(Out.DIM + "    … " + (lines.length - limit) + " more lines" + Out.RESET);
        }
    }

    private String readJournal() {
        try {
            var journalPath = Path.of(config.outputDir()).resolve(JOURNAL_FILE);
            if (Files.exists(journalPath)) {
                return Files.readString(journalPath);
            }
        } catch (Exception ignored) {}
        return "";
    }
}
