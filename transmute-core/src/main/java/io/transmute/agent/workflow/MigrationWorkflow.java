package io.transmute.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.transmute.agent.TransmuteConfig;
import io.transmute.agent.ModelFactory;
import io.transmute.agent.agent.FixCompileErrorsAgent;
import io.transmute.agent.agent.FixTestFailuresAgent;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.catalog.MigrationPlan;
import io.transmute.catalog.MigrationPlanner;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigration;
import io.transmute.migration.FileChange;
import io.transmute.migration.Migration;
import io.transmute.migration.MigrationResult;
import io.transmute.migration.MigrationScope;
import io.transmute.migration.Workspace;
import io.transmute.migration.postcheck.PostcheckRunner;
import io.transmute.tool.Ansi;
import io.transmute.tool.CompileProjectTool;
import io.transmute.tool.CopyProjectTool;
import io.transmute.tool.FileOperationsTool;
import io.transmute.tool.MigrationTools;
import io.transmute.tool.RunTestsTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the Transmute migration pipeline as plain sequential Java.
 *
 * <p>Pipeline (10 steps):
 * <ol>
 *   <li>Copy project to output dir</li>
 *   <li>Scan inventory</li>
 *   <li>Discover migrations</li>
 *   <li>Build migration plan</li>
 *   <li>Human approval (if !autoApprove)</li>
 *   <li>Execute migrations (Java migrations + AI recipes/features)</li>
 *   <li>Human review gate (if !autoApprove)</li>
 *   <li>Compile-fix loop (max {@value #MAX_FIX_ITERATIONS} iterations)</li>
 *   <li>Test-fix loop (max {@value #MAX_FIX_ITERATIONS} iterations)</li>
 *   <li>Generate report</li>
 * </ol>
 *
 * <p>AI is used only in steps 6 (recipe/feature agent calls) and 8–9 (fix agents).
 */
public class MigrationWorkflow {

    private static final int MAX_FIX_ITERATIONS = 5;
    private static final int TOTAL_STEPS = 10;
    private static final String JOURNAL_FILE = "migration-journal.md";

    private final TransmuteConfig config;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // Pipeline state — populated as steps execute
    private ProjectInventory inventory;
    private List<Migration> migrations;
    private MigrationPlan plan;
    private long migrationsExecuted;
    private long changedFiles;

    public MigrationWorkflow(TransmuteConfig config) {
        this.config = config;
    }

    public void run() throws Exception {
        printBanner();

        migrationsExecuted = 0;
        changedFiles = 0;

        copyProject();
        scanInventory();
        discoverMigrations();
        buildPlan();
        approvePlan();
        executeMigrations();
        reviewGate();
        compileFixLoop();
        testFixLoop();
        generateReport();

        System.out.println();
        System.out.println(Con.BOLD + Con.GREEN + "Migration complete." + Con.RESET
                + Con.DIM + "  Output: " + config.outputDir() + Con.RESET);
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private void copyProject() {
        Con.step(1, TOTAL_STEPS, "Copying project to output directory");
        var result = new CopyProjectTool().copyProjectResult(config.projectDir(), config.outputDir());
        if (result.success()) {
            Con.ok(result.message());
        } else {
            Con.error(result.message());
            throw new RuntimeException("Copy failed: " + result.message());
        }
        Con.rule();
    }

    private void scanInventory() {
        Con.step(2, TOTAL_STEPS, "Scanning project inventory");
        inventory = new io.transmute.inventory.JavaProjectScanner().scan(config.projectDir());
        Con.info("Scanned " + Con.bold(inventory.getJavaFiles().size() + " Java files"));
        Con.rule();
    }

    private void discoverMigrations() {
        Con.step(3, TOTAL_STEPS, "Discovering migrations");
        migrations = List.copyOf(new MarkdownMigrationLoader().load());
        Con.info("Found " + Con.bold(migrations.size() + " migrations"));
        Con.rule();
    }

    private void buildPlan() {
        Con.step(4, TOTAL_STEPS, "Building migration plan");
        plan = new MigrationPlanner().plan(migrations, inventory, List.of());
        Con.info("Migrations in plan: " + Con.bold(String.valueOf(plan.entries().size())));
        for (var entry : plan.entries()) {
            var line = new StringBuilder("  ")
                    .append(Con.CYAN).append(entry.migration().name()).append(Con.RESET)
                    .append(Con.DIM).append("  [").append(entry.confidence()).append("]").append(Con.RESET);
            if (!entry.targetFiles().isEmpty()) {
                line.append(Con.DIM).append("  ").append(entry.targetFiles().size()).append(" files").append(Con.RESET);
            }
            System.out.println(line);
            if (!entry.targetFiles().isEmpty()) {
                int shown = Math.min(5, entry.targetFiles().size());
                for (int i = 0; i < shown; i++) {
                    System.out.println(Con.DIM + "      " + Path.of(entry.targetFiles().get(i)).getFileName() + Con.RESET);
                }
                if (entry.targetFiles().size() > shown) {
                    System.out.println(Con.DIM + "      … " + (entry.targetFiles().size() - shown) + " more" + Con.RESET);
                }
            }
        }
        Con.rule();
    }

    private void approvePlan() {
        Con.step(5, TOTAL_STEPS, "Plan approval");
        if (config.autoApprove()) {
            Con.info(Con.DIM + "auto-approve" + Con.RESET);
            Con.rule();
            return;
        }
        String answer = readUserInput("\n  Proceed with migration? (yes/no): ");
        if (answer == null || !answer.trim().toLowerCase().startsWith("y")) {
            throw new RuntimeException("Migration aborted by user.");
        }
        Con.rule();
    }

    private void executeMigrations() {
        Con.step(6, TOTAL_STEPS, "Executing migrations");
        if (plan.entries().isEmpty()) {
            Con.warn("No migrations to execute.");
            Con.rule();
            return;
        }

        var workspace = new Workspace(config.projectDir(), config.outputDir(), config.dryRun());
        var model = ModelFactory.create();
        var postcheckRunner = new PostcheckRunner();

        var projectScoped = new ArrayList<AiMigration>();
        var byFile = new LinkedHashMap<String, List<AiMigration>>();
        for (var entry : plan.entries()) {
            var aiMig = (AiMigration) entry.migration();
            if (aiMig.skillScope() == MigrationScope.PROJECT) {
                projectScoped.add(aiMig);
            } else {
                for (var file : entry.targetFiles()) {
                    byFile.computeIfAbsent(file, k -> new ArrayList<>()).add(aiMig);
                }
            }
        }
        for (var aiMig : projectScoped) {
            if (applyToProject(aiMig, workspace, model)) {
                migrationsExecuted++;
            }
        }
        for (var e : byFile.entrySet()) {
            var result = applyToFile(e.getKey(), e.getValue(), workspace, model, postcheckRunner);
            if (result.success()) {
                migrationsExecuted += e.getValue().size();
            }
            if (result.changed()) {
                changedFiles++;
            }
        }

        Con.ok("Migration execution complete");
        Con.rule();
    }

    private void reviewGate() {
        Con.step(7, TOTAL_STEPS, "Review changes");
        if (config.autoApprove()) {
            Con.info(Con.DIM + "auto-approve" + Con.RESET);
            Con.rule();
            return;
        }
        System.out.println("  Output: " + config.outputDir());
        String answer = readUserInput("\n  Approve and proceed to compile+test? (yes/no): ");
        if (answer == null || !answer.trim().toLowerCase().startsWith("y")) {
            throw new RuntimeException("Migration stopped at review step.");
        }
        Con.rule();
    }

    private void compileFixLoop() {
        Con.step(8, TOTAL_STEPS, "Compile-fix loop  (max " + MAX_FIX_ITERATIONS + " attempts)");
        for (int i = 1; i <= MAX_FIX_ITERATIONS; i++) {
            System.out.println("  " + Con.DIM + "Compiling… [" + i + "/" + MAX_FIX_ITERATIONS + "]" + Con.RESET);
            var result = new CompileProjectTool(config.activeProfiles()).runCompile(config.outputDir());
            if (result.success()) {
                Con.ok("Compilation successful");
                Con.rule();
                return;
            }
            Con.error("Compilation failed (attempt " + i + "/" + MAX_FIX_ITERATIONS + ")");
            printFirstLines(result.errors(), 30);
            if (i == MAX_FIX_ITERATIONS) {
                throw new RuntimeException("Compile failed after " + MAX_FIX_ITERATIONS + " attempts.");
            }
            System.out.println("  " + Con.YELLOW + "→ Invoking compile-fix agent…" + Con.RESET);
            buildCompileFixAgent().fix(config.outputDir(), result.errors(), readJournal());
        }
        Con.rule();
    }

    private void testFixLoop() {
        Con.step(9, TOTAL_STEPS, "Test-fix loop  (max " + MAX_FIX_ITERATIONS + " attempts)");
        for (int i = 1; i <= MAX_FIX_ITERATIONS; i++) {
            System.out.println("  " + Con.DIM + "Running tests… [" + i + "/" + MAX_FIX_ITERATIONS + "]" + Con.RESET);
            var result = new RunTestsTool(config.activeProfiles()).runMvnTest(config.outputDir());
            if (result.success()) {
                Con.ok("All tests passed");
                Con.rule();
                return;
            }
            Con.error("Tests failed (attempt " + i + "/" + MAX_FIX_ITERATIONS + ")");
            printFirstLines(result.output(), 30);
            if (i == MAX_FIX_ITERATIONS) {
                throw new RuntimeException("Tests failed after " + MAX_FIX_ITERATIONS + " attempts.");
            }
            System.out.println("  " + Con.YELLOW + "→ Invoking test-fix agent…" + Con.RESET);
            buildTestFixAgent().fix(config.outputDir(), result.output(), readJournal());
        }
        Con.rule();
    }

    private void generateReport() throws Exception {
        Con.step(10, TOTAL_STEPS, "Generating migration report");
        var report = new LinkedHashMap<String, Object>();
        report.put("sourceDir", config.projectDir());
        report.put("outputDir", config.outputDir());
        report.put("migrationsExecuted", migrationsExecuted);
        report.put("filesChanged", changedFiles);
        report.put("dryRun", config.dryRun());

        var reportPath = Path.of(config.outputDir(), "migration-report.json");
        if (!config.dryRun()) {
            json.writeValue(reportPath.toFile(), report);
            Con.ok("Report written to: " + Con.DIM + reportPath + Con.RESET);
        } else {
            Con.info(Con.DIM + "[dry-run] Would write report to: " + reportPath + Con.RESET);
        }
        Con.rule();
    }

    // ── AI agent factories ────────────────────────────────────────────────────

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

    // ── AI recipe/feature execution ───────────────────────────────────────────

    /**
     * Applies a project-scoped recipe/feature once for the whole output directory.
     */
    private boolean applyToProject(
            AiMigration aiMigration,
            Workspace workspace,
            dev.langchain4j.model.chat.ChatModel model) {

        System.out.println("  " + Con.CYAN + "⬡ " + Con.RESET
                + Con.BOLD + aiMigration.skillName() + Con.RESET
                + Con.DIM + "  (project)" + Con.RESET);

        if (config.dryRun()) {
            System.out.println("    " + Con.DIM + "[dry-run] skipping agent invocation" + Con.RESET);
            return false;
        }
        try {
            AiServices.builder(SingleFileAgent.class)
                    .chatModel(model)
                    .tools(new FileOperationsTool(workspace.outputDir()))
                    .systemMessageProvider(id -> aiMigration.systemPromptSection())
                    .build()
                    .apply("Apply the migration to the project at: " + workspace.outputDir()
                            + "\nUse paths relative to the output directory."
                            + "\nAfter completing changes, append a summary to " + JOURNAL_FILE
                            + " using append_file (what you changed, key decisions, edge cases).");
            return true;
        } catch (Exception e) {
            Con.error("Agent failed for project migration " + aiMigration.skillName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Applies one or more recipes/features to a single file in a single agent invocation.
     */
    private ApplyResult applyToFile(
            String sourceFile,
            List<AiMigration> aiMigrations,
            Workspace workspace,
            dev.langchain4j.model.chat.ChatModel model,
            PostcheckRunner postcheckRunner) {

        var sourceDirAbs = Path.of(workspace.sourceDir()).toAbsolutePath().normalize();
        var relPath = sourceDirAbs.relativize(sourceDirAbs.resolve(sourceFile).normalize()).toString();
        var outputDir = workspace.outputDir();

        String beforeContent;
        try {
            beforeContent = Files.readString(Path.of(outputDir).resolve(relPath));
        } catch (Exception e) {
            Con.error("Cannot read " + relPath + ": " + e.getMessage());
            return ApplyResult.failed();
        }

        var migrationNames = aiMigrations.stream().map(AiMigration::skillName).toList();
        System.out.println("  " + Con.CYAN + "◆ " + Con.RESET
                + Con.BOLD + Path.of(relPath).getFileName() + Con.RESET
                + "  " + Con.DIM
                + migrationNames.stream().map(n -> "[" + n + "]").collect(Collectors.joining(" "))
                + Con.RESET);

        if (config.dryRun()) {
            System.out.println("    " + Con.DIM + "[dry-run] skipping agent invocation" + Con.RESET);
            return ApplyResult.failed();
        }

        var combinedPrompt = buildCombinedPrompt(aiMigrations);
        try {
            AiServices.builder(SingleFileAgent.class)
                    .chatModel(model)
                    .tools(new FileOperationsTool(outputDir))
                    .systemMessageProvider(id -> combinedPrompt)
                    .build()
                    .apply("Apply all migrations to: " + relPath + "\n"
                            + "Output directory: " + outputDir + "\n"
                            + "Read the file, apply every section above, write it back.");
        } catch (Exception e) {
            Con.error("Agent failed for " + relPath + ": " + e.getMessage());
            return ApplyResult.failed();
        }

        String afterContent;
        try {
            afterContent = Files.readString(Path.of(outputDir).resolve(relPath));
        } catch (Exception e) {
            Con.error("Cannot read result for " + relPath + ": " + e.getMessage());
            return ApplyResult.failed();
        }

        var change = new FileChange(sourceFile, beforeContent, afterContent);
        var result = MigrationResult.success(List.of(change), List.of(), "migration applied");

        for (var aiMigration : aiMigrations) {
            var failures = postcheckRunner.runMarkdownPostchecks(aiMigration.skillPostchecks(), result);
            if (!failures.isEmpty()) {
                Con.warn("Postcheck failures (" + aiMigration.skillName() + ") for " + relPath + ":");
                failures.forEach(f -> System.out.println("      " + Con.YELLOW + f + Con.RESET));
            }
        }
        return new ApplyResult(true, change.isChanged());
    }

    /**
     * Builds a combined system prompt merging all recipes and features into sections.
     */
    private String buildCombinedPrompt(List<AiMigration> aiMigrations) {
        var sb = new StringBuilder();
        sb.append("""
                You are an expert Java developer executing a framework migration.
                Apply ALL sections below. Each section declares what it owns and what transformations to apply.
                Do not modify anything not covered by a section below.

                ## Migration Journal
                After completing your changes, append a brief summary line to \
                """)
          .append(JOURNAL_FILE)
          .append("""
                 using the append_file tool.
                Include: what migration(s) you applied, which file you changed, and any \
                decisions or edge cases worth noting for subsequent migrations or fix agents.
                """);

        for (var migration : aiMigrations) {
            sb.append("\n## ").append(migration.skillName());
            var owned = Stream.concat(
                    migration.transformAnnotations().stream(),
                    migration.transformTypes().stream()).toList();
            if (!owned.isEmpty()) {
                sb.append(" (owns: ").append(String.join(", ", owned)).append(")");
            }
            sb.append("\n");

            var doNotTouch = aiMigrations.stream()
                    .filter(other -> other != migration)
                    .flatMap(other -> Stream.concat(
                            other.transformAnnotations().stream(),
                            other.transformTypes().stream()))
                    .distinct().toList();
            if (!doNotTouch.isEmpty()) {
                sb.append("DO NOT touch: ").append(String.join(", ", doNotTouch))
                  .append(" (handled by other sections)\n");
            }
            sb.append(migration.systemPromptSection()).append("\n");
        }
        return sb.toString();
    }

    /** Single-turn AI service used to apply recipes/features to one file. */
    private interface SingleFileAgent {
        @UserMessage("{{msg}}")
        String apply(@V("msg") String msg);
    }

    private record ApplyResult(boolean success, boolean changed) {
        private static ApplyResult failed() {
            return new ApplyResult(false, false);
        }
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    private static void printBanner() {
        // ASCII art generated with "slant" style lettering
        String c = Con.CYAN + Con.BOLD;
        String r = Con.RESET;
        String d = Con.DIM;
        System.out.println();
        System.out.println(c + "  ████████╗██████╗  █████╗ ███╗   ██╗███████╗███╗   ███╗██╗   ██╗████████╗███████╗" + r);
        System.out.println(c + "     ██╔══╝██╔══██╗██╔══██╗████╗  ██║██╔════╝████╗ ████║██║   ██║╚══██╔══╝██╔════╝" + r);
        System.out.println(c + "     ██║   ██████╔╝███████║██╔██╗ ██║███████╗██╔████╔██║██║   ██║   ██║   █████╗  " + r);
        System.out.println(c + "     ██║   ██╔══██╗██╔══██║██║╚██╗██║╚════██║██║╚██╔╝██║██║   ██║   ██║   ██╔══╝  " + r);
        System.out.println(c + "     ██║   ██║  ██║██║  ██║██║ ╚████║███████║██║ ╚═╝ ██║╚██████╔╝   ██║   ███████╗" + r);
        System.out.println(c + "     ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═══╝╚══════╝╚═╝     ╚═╝ ╚═════╝    ╚═╝   ╚══════╝" + r);
        System.out.println(d + "                        Framework migration powered by AI" + r);
        System.out.println();
    }

    // ── Console formatting ────────────────────────────────────────────────────

    /** ANSI-colored console helpers. */
    private static final class Con {
        static final String RESET  = Ansi.RESET;
        static final String BOLD   = Ansi.BOLD;
        static final String DIM    = Ansi.DIM;
        static final String CYAN   = Ansi.CYAN;
        static final String GREEN  = Ansi.GREEN;
        static final String RED    = Ansi.RED;
        static final String YELLOW = Ansi.YELLOW;

        private static final String RULE_STR = DIM + "─".repeat(80) + RESET;

        static void step(int n, int total, String title) {
            System.out.println();
            System.out.println(BOLD + CYAN + "[" + n + "/" + total + "]" + RESET
                    + BOLD + "  " + title + RESET);
        }

        static void rule() {
            System.out.println(RULE_STR);
        }

        static void ok(String msg) {
            System.out.println("  " + GREEN + "✓" + RESET + "  " + msg);
        }

        static void info(String msg) {
            System.out.println("  " + msg);
        }

        static void warn(String msg) {
            System.out.println("  " + YELLOW + "⚠" + RESET + "  " + msg);
        }

        static void error(String msg) {
            System.err.println("  " + RED + "✗" + RESET + "  " + msg);
        }

        static String bold(String text) {
            return BOLD + text + RESET;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String readUserInput(String prompt) {
        System.out.print(prompt);
        var console = System.console();
        if (console != null) {
            return console.readLine();
        }
        try {
            return new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        } catch (Exception e) {
            return "no";
        }
    }

    private void printFirstLines(String text, int limit) {
        if (text == null || text.isBlank()) return;
        var lines = text.split("\\R");
        int count = Math.min(limit, lines.length);
        for (int i = 0; i < count; i++) {
            System.out.println(Con.DIM + "    " + lines[i] + Con.RESET);
        }
        if (lines.length > limit) {
            System.out.println(Con.DIM + "    … " + (lines.length - limit) + " more lines" + Con.RESET);
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
