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
import io.transmute.agent.agent.ProjectAnalysisAgent;
import io.transmute.agent.agent.ReconciliationAgent;
import io.transmute.inventory.DependencyInfo;
import io.transmute.inventory.JavaFileInfo;
import io.transmute.catalog.DependencyCatalogEntry;
import io.transmute.catalog.DependencyStatus;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.catalog.MigrationPlan;
import io.transmute.catalog.MigrationPlanner;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigration;
import io.transmute.migration.FileChange;
import io.transmute.migration.Migration;
import io.transmute.migration.MigrationResult;
import io.transmute.migration.MigrationScope;
import io.transmute.migration.RecipeKind;
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
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the Transmute migration pipeline as plain sequential Java.
 *
 * <p>Pipeline (11 steps):
 * <ol>
 *   <li>Copy project to output dir</li>
 *   <li>Scan inventory</li>
 *   <li>Analyze project (AI produces a structured summary for recipe context)</li>
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
 * <p>AI is used in steps 3 (project analysis), 7 (recipe/feature agent calls),
 * and 9–10 (fix agents).
 */
public class MigrationWorkflow {

    private static final int MAX_FIX_ITERATIONS = 5;
    private static final int TOTAL_STEPS = 11;
    private static final String JOURNAL_FILE = "migration-journal.md";

    private final TransmuteConfig config;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final MarkdownMigrationLoader loader = new MarkdownMigrationLoader();
    private final MarkdownMigrationLoader.Hints hints = loader.loadHints();
    private final MarkdownMigrationLoader.Catalog catalog = loader.loadCatalog();

    // Pipeline state — populated as steps execute
    private ProjectInventory inventory;
    private String projectSummary = "";
    private List<Migration> migrations;
    private MigrationPlan plan;
    private long migrationsExecuted;
    private long changedFiles;

    public MigrationWorkflow(TransmuteConfig config) {
        this.config = config;
    }

    public void run() throws Exception {
        printBanner();
        printConfig();

        migrationsExecuted = 0;
        changedFiles = 0;

        copyProject();
        scanInventory();
        analyzeProject();
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

    private void analyzeProject() {
        Con.step(3, TOTAL_STEPS, "Analyzing project structure");
        try {
            var inventorySummary = buildInventorySummary();
            var keyFileContents = readKeyFiles();
            projectSummary = AiServices.builder(ProjectAnalysisAgent.class)
                    .chatModel(ModelFactory.create())
                    .build()
                    .analyze(inventorySummary, keyFileContents);
            Con.ok("Project analysis complete (" + projectSummary.lines().count() + " lines)");
        } catch (Exception e) {
            Con.warn("Project analysis failed (continuing without): " + e.getMessage());
            projectSummary = "";
        }
        Con.rule();
    }

    private void discoverMigrations() {
        Con.step(4, TOTAL_STEPS, "Discovering migrations");
        migrations = List.copyOf(loader.load());
        Con.info("Found " + Con.bold(migrations.size() + " migrations"));
        Con.rule();
    }

    private void buildPlan() {
        Con.step(5, TOTAL_STEPS, "Building migration plan");
        plan = new MigrationPlanner().plan(migrations, inventory, List.of());
        var view = buildPlanView();
        printPlanView(view);
        savePlanToFile(view);
        Con.rule();
    }

    // ── Plan view ──────────────────────────────────────────────────────────────

    /**
     * Pre-computed, file-centric view of the migration plan.
     *
     * @param coveredFiles ordered list of all files targeted by at least one migration
     * @param fileRecipes  file → names of RECIPE-kind migrations that target it
     * @param fileFeatures file → names of FEATURE-kind migrations that target it
     * @param projectScoped names of project-scoped migrations (no target files)
     * @param totalSrc     source files in inventory (excludes test files)
     * @param totalTest    test files in inventory
     */
    private record PlanView(
            List<String> coveredFiles,
            Map<String, List<String>> fileRecipes,
            Map<String, List<String>> fileFeatures,
            List<String> projectScoped,
            int totalSrc,
            int totalTest
    ) {}

    private PlanView buildPlanView() {
        var fileRecipes  = new LinkedHashMap<String, List<String>>();
        var fileFeatures = new LinkedHashMap<String, List<String>>();
        var projectScoped = new ArrayList<String>();

        for (var entry : plan.entries()) {
            var migration = (AiMigration) entry.migration();
            if (entry.targetFiles().isEmpty()) {
                projectScoped.add(migration.name());
            } else {
                for (var file : entry.targetFiles()) {
                    if (migration.skillType() == RecipeKind.RECIPE) {
                        fileRecipes.computeIfAbsent(file, k -> new ArrayList<>()).add(migration.name());
                    } else {
                        fileFeatures.computeIfAbsent(file, k -> new ArrayList<>()).add(migration.name());
                    }
                }
            }
        }

        var coveredSet = new LinkedHashSet<String>();
        fileRecipes.keySet().forEach(coveredSet::add);
        fileFeatures.keySet().forEach(coveredSet::add);

        int testFiles = (int) inventory.getJavaFiles().stream()
                .filter(f -> f.sourceFile().replace('\\', '/').contains("/test/"))
                .count();
        int srcFiles = inventory.getJavaFiles().size() - testFiles;

        return new PlanView(
                List.copyOf(coveredSet),
                fileRecipes,
                fileFeatures,
                List.copyOf(projectScoped),
                srcFiles,
                testFiles);
    }

    private void printPlanView(PlanView v) {
        int covered    = v.coveredFiles().size();
        int notTargeted = v.totalSrc() - covered;

        // ── Overview ──────────────────────────────────────────────────────────
        System.out.println();
        Con.sectionHeader("Project Overview");
        System.out.printf("  Source files:  %3d   │  Test files:     %3d%n", v.totalSrc(), v.totalTest());
        System.out.printf("  Covered:       %3d   │  Not targeted:   %3d%n", covered, notTargeted);
        System.out.printf("  Migrations:    %3d   │  Project-scoped: %3d%n",
                plan.entries().size(), v.projectScoped().size());

        // ── Dependencies ──────────────────────────────────────────────────────
        var deps = inventory.getDependencies();
        if (!deps.isEmpty()) {
            long replaced    = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.REPLACED).count();
            long partial     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.PARTIAL).count();
            long unsupported = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNSUPPORTED).count();
            long unknown     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNKNOWN).count();
            var subtitle = new java.util.StringJoiner(" · ");
            if (replaced    > 0) subtitle.add(replaced    + " replaced");
            if (partial     > 0) subtitle.add(partial     + " partial");
            if (unsupported > 0) subtitle.add(unsupported + " unsupported");
            if (unknown     > 0) subtitle.add(unknown     + " unknown");
            System.out.println();
            Con.sectionHeader("Dependencies", subtitle.toString());
            for (var dep : deps) {
                var coord = dep.groupId() + ":" + dep.artifactId()
                        + (dep.version() != null && !dep.version().isBlank() ? ":" + dep.version() : "");
                var status = getCatalogStatus(dep);
                var entry = getCatalogEntry(dep);
                var noteStr = (entry != null && entry.notes() != null && !entry.notes().isBlank())
                        ? Con.DIM + " - " + entry.notes() + Con.RESET : "";
                if (status == DependencyStatus.REPLACED) {
                    System.out.println("  " + Con.GREEN + "[+]" + Con.RESET + "  " + coord + noteStr);
                } else if (status == DependencyStatus.PARTIAL) {
                    System.out.println("  " + Con.YELLOW + "[~]" + Con.RESET + "  " + coord + noteStr);
                } else if (status == DependencyStatus.UNSUPPORTED) {
                    System.out.println("  " + Con.RED + "[!]" + Con.RESET + "  " + coord + noteStr);
                } else if (status == DependencyStatus.PASSTHROUGH) {
                    System.out.println("  " + Con.DIM + "[=]  " + coord + Con.RESET);
                } else {
                    System.out.println("  " + Con.DIM + "[?]  " + coord + Con.RESET);
                }
            }
            System.out.println("  " + Con.DIM
                    + "[+] replaced  [~] partial  [!] unsupported  [=] passthrough  [?] unknown"
                    + Con.RESET);
        }

        // ── Project-scoped ────────────────────────────────────────────────────
        if (!v.projectScoped().isEmpty()) {
            System.out.println();
            Con.sectionHeader("Project-scoped", "run once, not file-specific");
            for (var name : v.projectScoped()) {
                System.out.println("  " + Con.CYAN + "•" + Con.RESET + "  " + name);
            }
        }

        // ── File plan table ───────────────────────────────────────────────────
        if (!v.coveredFiles().isEmpty()) {
            System.out.println();
            Con.sectionHeader("File Migration Plan",
                    covered + " files · " + v.fileRecipes().size() + " with recipe · "
                    + v.fileFeatures().size() + " with features");
            int wFile    = 40;
            int wRecipe  = 28;
            System.out.println("  "
                    + Con.DIM + padRight("File", wFile) + "  "
                    + padRight("Recipe", wRecipe) + "  "
                    + "Features" + Con.RESET);
            System.out.println("  "
                    + Con.DIM + "─".repeat(wFile) + "  "
                    + "─".repeat(wRecipe) + "  "
                    + "─".repeat(44) + Con.RESET);
            for (var file : v.coveredFiles()) {
                var recipes  = v.fileRecipes().getOrDefault(file, List.of());
                var features = v.fileFeatures().getOrDefault(file, List.of());
                var fileInfo = inventory.fileByPath(file);
                var keyImports = fileInfo != null
                        ? fileInfo.imports().stream()
                                .filter(i -> !i.startsWith("java.") && !i.startsWith("javax.")
                                        && !i.startsWith("jakarta.") && !i.startsWith("org.junit")
                                        && !i.startsWith("org.mockito"))
                                .limit(2).toList()
                        : List.<String>of();

                var featStr = features.isEmpty() ? Con.DIM + "—" + Con.RESET
                        : features.stream().collect(java.util.stream.Collectors.joining(", "));

                // Build the framework-hints column (key framework imports, dimmed)
                var importHint = keyImports.isEmpty() ? ""
                        : Con.DIM + "  [" + keyImports.stream()
                                .map(i -> i.substring(i.lastIndexOf('.') + 1))
                                .collect(java.util.stream.Collectors.joining(", ")) + "]" + Con.RESET;

                String recipePadded = recipes.isEmpty()
                        ? Con.DIM + padRight("—", wRecipe) + Con.RESET
                        : padRight(truncate(String.join(", ", recipes), wRecipe), wRecipe);
                System.out.println("  "
                        + padRight(truncate(shortPath(file), wFile), wFile) + "  "
                        + recipePadded + "  "
                        + featStr + importHint);
            }
        }

        // ── Not targeted ──────────────────────────────────────────────────────
        var untouched = inventory.getJavaFiles().stream()
                .filter(f -> !v.coveredFiles().contains(f.sourceFile()))
                .filter(f -> !f.sourceFile().replace('\\', '/').contains("/test/"))
                .map(f -> shortPath(f.sourceFile()))
                .toList();
        if (!untouched.isEmpty()) {
            System.out.println();
            Con.sectionHeader("Not targeted", untouched.size() + " files");
            int shown = Math.min(8, untouched.size());
            for (int i = 0; i < shown; i++) {
                System.out.println("  " + Con.DIM + "·  " + untouched.get(i) + Con.RESET);
            }
            if (untouched.size() > shown) {
                System.out.println("  " + Con.DIM + "·  … " + (untouched.size() - shown) + " more" + Con.RESET);
            }
        }
    }

    private void savePlanToFile(PlanView v) {
        try {
            var sb = new StringBuilder();
            int covered     = v.coveredFiles().size();
            int notTargeted = v.totalSrc() - covered;

            txtSection(sb, "Migration Plan");
            sb.append("Generated : ").append(java.time.Instant.now()).append("\n");
            sb.append("Project   : ").append(config.projectDir()).append("\n\n");

            txtSection(sb, "Project Overview");
            sb.append(String.format("  Source files  : %d%n", v.totalSrc()));
            sb.append(String.format("  Test files    : %d%n", v.totalTest()));
            sb.append(String.format("  Covered       : %d%n", covered));
            sb.append(String.format("  Not targeted  : %d%n", notTargeted));
            sb.append(String.format("  Migrations    : %d%n", plan.entries().size()));
            sb.append(String.format("  Project-scoped: %d%n", v.projectScoped().size()));
            sb.append("\n");

            var deps = inventory.getDependencies();
            if (!deps.isEmpty()) {
                long replaced    = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.REPLACED).count();
                long partial     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.PARTIAL).count();
                long unsupported = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNSUPPORTED).count();
                long unknown     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNKNOWN).count();
                var counts = new java.util.StringJoiner(" · ");
                if (replaced    > 0) counts.add(replaced    + " replaced");
                if (partial     > 0) counts.add(partial     + " partial");
                if (unsupported > 0) counts.add(unsupported + " unsupported");
                if (unknown     > 0) counts.add(unknown     + " unknown");
                txtSection(sb, "Dependencies  (" + counts + ")");
                for (var dep : deps) {
                    var status = getCatalogStatus(dep);
                    var symbol = switch (status) {
                        case REPLACED    -> "[+]";
                        case PARTIAL     -> "[~]";
                        case UNSUPPORTED -> "[!]";
                        case PASSTHROUGH -> "[=]";
                        default          -> "[?]";
                    };
                    var coord = dep.groupId() + ":" + dep.artifactId()
                            + (dep.version() != null && !dep.version().isBlank() ? ":" + dep.version() : "");
                    var entry = getCatalogEntry(dep);
                    var note = (entry != null && entry.notes() != null && !entry.notes().isBlank())
                            ? "  - " + entry.notes() : "";
                    sb.append("  ").append(symbol).append("  ").append(coord).append(note).append("\n");
                }
                sb.append("\n");
            }

            if (!v.projectScoped().isEmpty()) {
                txtSection(sb, "Project-scoped Migrations");
                for (var name : v.projectScoped()) {
                    sb.append("  - ").append(name).append("\n");
                }
                sb.append("\n");
            }

            if (!v.coveredFiles().isEmpty()) {
                txtSection(sb, "File Migration Plan");
                int wFile = v.coveredFiles().stream()
                        .mapToInt(f -> f.length()).max().orElse(40) + 2;
                int wRecipe = v.fileRecipes().values().stream()
                        .mapToInt(r -> String.join(", ", r).length()).max().orElse(20) + 2;
                var hdr = padRight("File", wFile) + padRight("Recipe", wRecipe) + "Features";
                sb.append("  ").append(hdr).append("\n");
                sb.append("  ").append("─".repeat(hdr.length())).append("\n");
                for (var file : v.coveredFiles()) {
                    var recipes  = v.fileRecipes().getOrDefault(file, List.of());
                    var features = v.fileFeatures().getOrDefault(file, List.of());
                    sb.append("  ")
                            .append(padRight(file, wFile))
                            .append(padRight(recipes.isEmpty() ? "—" : String.join(", ", recipes), wRecipe))
                            .append(features.isEmpty() ? "—" : String.join(", ", features))
                            .append("\n");
                }
                sb.append("\n");
            }

            var untouched = inventory.getJavaFiles().stream()
                    .filter(f -> !v.coveredFiles().contains(f.sourceFile()))
                    .filter(f -> !f.sourceFile().replace('\\', '/').contains("/test/"))
                    .map(JavaFileInfo::sourceFile)
                    .toList();
            if (!untouched.isEmpty()) {
                txtSection(sb, "Not Targeted  (" + untouched.size() + " files)");
                for (var f : untouched) {
                    sb.append("  ").append(f).append("\n");
                }
                sb.append("\n");
            }

            var planPath = Path.of(config.outputDir()).resolve("migration-plan.txt");
            Files.writeString(planPath, sb.toString());
            System.out.println();
            Con.info(Con.DIM + "Plan saved → " + Con.RESET + planPath.getFileName());
        } catch (Exception e) {
            Con.warn("Could not save plan file: " + e.getMessage());
        }
    }

    /** Appends a plain-text section header (title + underline of title.length()+1 dashes + blank line). */
    private static void txtSection(StringBuilder sb, String title) {
        sb.append(title).append("\n");
        sb.append("─".repeat(title.length() + 1)).append("\n");
        sb.append("\n");
    }

    private DependencyCatalogEntry getCatalogEntry(DependencyInfo dep) {
        var entry = catalog.entries().get(dep.groupId() + ":" + dep.artifactId());
        if (entry == null) entry = catalog.entries().get(dep.groupId() + ":*");
        return entry;
    }

    private DependencyStatus getCatalogStatus(DependencyInfo dep) {
        var entry = getCatalogEntry(dep);
        return entry != null ? entry.status() : DependencyStatus.PASSTHROUGH;
    }

    private String buildCatalogHints() {
        var relevant = inventory.getDependencies().stream()
                .filter(d -> {
                    var e = getCatalogEntry(d);
                    return e != null && (e.status() == DependencyStatus.UNSUPPORTED
                                     || e.status() == DependencyStatus.PARTIAL);
                })
                .toList();
        if (relevant.isEmpty()) return "";
        var sb = new StringBuilder("## Known Dependency Status\n");
        for (var dep : relevant) {
            var e = getCatalogEntry(dep);
            sb.append("- **").append(dep.groupId()).append(":").append(dep.artifactId())
              .append("** (").append(e.status().name().toLowerCase()).append(")");
            if (e.notes() != null && !e.notes().isBlank())
                sb.append(": ").append(e.notes());
            sb.append("\n");
        }
        return sb.toString();
    }

    private void approvePlan() {
        Con.step(6, TOTAL_STEPS, "Plan approval");
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
        Con.step(7, TOTAL_STEPS, "Executing migrations");
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
        Con.step(8, TOTAL_STEPS, "Review changes");
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
        Con.step(9, TOTAL_STEPS, "Compile-fix loop  (max " + MAX_FIX_ITERATIONS + " attempts)");
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
            var catalogSection = buildCatalogHints();
            var effectiveCompileHints = Stream.of(catalogSection, hints.compileHints())
                    .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("\n\n"));
            buildCompileFixAgent().fix(config.outputDir(), result.errors(), projectSummary, readJournal(), effectiveCompileHints);
        }
        Con.rule();
    }

    private void testFixLoop() {
        Con.step(10, TOTAL_STEPS, "Test-fix loop  (max " + MAX_FIX_ITERATIONS + " attempts)");
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
            var catalogSection = buildCatalogHints();
            var effectiveTestHints = Stream.of(catalogSection, hints.testHints())
                    .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("\n\n"));
            buildTestFixAgent().fix(config.outputDir(), result.output(), projectSummary, readJournal(), effectiveTestHints);
        }
        Con.rule();
    }

    private void generateReport() throws Exception {
        Con.step(11, TOTAL_STEPS, "Generating migration report");
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

        System.out.println("  " + Con.CYAN + ">> " + Con.RESET
                + Con.BOLD + aiMigration.skillName() + Con.RESET
                + Con.DIM + "  (project)" + Con.RESET);

        if (config.dryRun()) {
            System.out.println("    " + Con.DIM + "[dry-run] skipping agent invocation" + Con.RESET);
            return false;
        }
        try {
            var systemPrompt = aiMigration.systemPromptSection()
                    + (projectSummary.isBlank() ? "" : "\n\n## Project Context\n" + projectSummary);
            AiServices.builder(SingleFileAgent.class)
                    .chatModel(model)
                    .tools(new FileOperationsTool(workspace.outputDir()))
                    .systemMessageProvider(id -> systemPrompt)
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
        System.out.println("  " + Con.CYAN + ">> " + Con.RESET
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

        if (!projectSummary.isBlank()) {
            sb.append("\n## Project Context\n").append(projectSummary).append("\n");
        }

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

    private void printConfig() {
        var d = Con.DIM;
        var r = Con.RESET;
        System.out.println(d + "  Model provider:  " + r + config.modelProvider());
        System.out.println(d + "  Model id:        " + r + resolvedModelId());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            System.out.println(d + "  Model base URL:  " + r + config.baseUrl());
        }
        if (config.forceHttp1()) {
            System.out.println(d + "  Force HTTP 1.1:  " + r + "yes");
        }
        System.out.println(d + "  Project:         " + r + config.projectDir());
        System.out.println(d + "  Output:          " + r + config.outputDir());
        System.out.println(d + "  Dry run:         " + r + (config.dryRun() ? "yes" : "no"));
        System.out.println(d + "  Auto-approve:    " + r + (config.autoApprove() ? "yes" : "no"));
        System.out.println(d + "  Verbose:         " + r + (config.verbose() ? "yes" : "no"));
        System.out.println();
    }

    private String resolvedModelId() {
        return switch (config.modelProvider()) {
            case "oci-genai" -> config.modelId("cohere.command-r-plus");
            case "openai"    -> config.modelId("gpt-4o");
            case "ollama"    -> config.modelId("llama3.3");
            default          -> config.modelId();
        };
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

        /**
         * Prints a header line, an underline of {@code title.length() + 1} dashes, and a blank line.
         * Use {@code title} for the plain visible text; {@code subtitle} (dimmed) appended after two spaces.
         */
        static void sectionHeader(String title, String subtitle) {
            System.out.println("  " + BOLD + title + RESET
                    + (subtitle.isEmpty() ? "" : "  " + DIM + subtitle + RESET));
            System.out.println("  " + DIM + "─".repeat(title.length() + 1) + RESET);
            System.out.println();
        }

        static void sectionHeader(String title) {
            sectionHeader(title, "");
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

    private static String shortPath(String path) {
        var p = Path.of(path.replace('\\', '/'));
        int n = p.getNameCount();
        if (n >= 2) return p.getName(n - 2) + "/" + p.getFileName();
        return p.getFileName() != null ? p.getFileName().toString() : path;
    }

    private static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String stripAnsi(String s) {
        return s == null ? "" : s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

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

    private String buildInventorySummary() {
        var sb = new StringBuilder();
        sb.append("Java files: ").append(inventory.getJavaFiles().size()).append("\n\n");
        for (var file : inventory.getJavaFiles()) {
            sb.append("### ").append(file.className()).append("\n");
            sb.append("  File: ").append(file.sourceFile()).append("\n");
            if (!file.superTypes().isEmpty()) {
                sb.append("  Extends/implements: ").append(String.join(", ", file.superTypes())).append("\n");
            }
            if (!file.annotationTypes().isEmpty()) {
                sb.append("  Annotations: ").append(String.join(", ", file.annotationTypes())).append("\n");
            }
            var keyImports = file.imports().stream()
                    .filter(i -> !i.startsWith("java.") && !i.startsWith("javax."))
                    .sorted()
                    .toList();
            if (!keyImports.isEmpty()) {
                sb.append("  Key imports: ").append(String.join(", ", keyImports)).append("\n");
            }
            sb.append("\n");
        }
        if (!inventory.getDependencies().isEmpty()) {
            sb.append("## Dependencies\n");
            for (var dep : inventory.getDependencies()) {
                sb.append("  ").append(dep).append("\n");
            }
        }
        return sb.toString();
    }

    private String readKeyFiles() {
        var sb = new StringBuilder();
        var projectRoot = Path.of(config.projectDir());

        // Read build files
        for (var buildFile : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            var path = projectRoot.resolve(buildFile);
            if (Files.exists(path)) {
                appendFileContent(sb, buildFile, path);
            }
        }

        // Read Application class, Configuration class, and a few representative sources
        var interesting = inventory.getJavaFiles().stream()
                .filter(f -> f.superTypes().stream().anyMatch(st ->
                        st.contains("Application") || st.contains("Configuration")
                                || st.contains("Managed") || st.contains("HealthCheck")))
                .limit(5)
                .toList();
        for (var file : interesting) {
            var path = projectRoot.resolve(file.sourceFile());
            if (Files.exists(path)) {
                appendFileContent(sb, file.sourceFile(), path);
            }
        }

        // Read a couple of representative resources (REST endpoints)
        var resources = inventory.getJavaFiles().stream()
                .filter(f -> f.imports().stream().anyMatch(i -> i.contains("javax.ws.rs") || i.contains("jakarta.ws.rs")))
                .limit(2)
                .toList();
        for (var file : resources) {
            if (interesting.contains(file)) continue;
            var path = projectRoot.resolve(file.sourceFile());
            if (Files.exists(path)) {
                appendFileContent(sb, file.sourceFile(), path);
            }
        }

        return sb.toString();
    }

    private void appendFileContent(StringBuilder sb, String label, Path path) {
        try {
            var content = Files.readString(path);
            // Truncate very large files
            if (content.length() > 5000) {
                content = content.substring(0, 5000) + "\n... (truncated)";
            }
            sb.append("### ").append(label).append("\n```\n").append(content).append("\n```\n\n");
        } catch (Exception ignored) {}
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
