package io.transmute.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.transmute.agent.TransmuteConfig;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.catalog.MigrationPlan;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigration;
import io.transmute.migration.FileOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Steps 9 + 12: TODO scan + {@code migration-todos.txt} + {@code migration-results.txt}
 * + {@code migration-report.json}.
 */
class ReportWriter {

    private final TransmuteConfig config;
    private final MigrationPlan plan;
    private final ProjectInventory inventory;
    private final MarkdownMigrationLoader.Catalog catalog;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    ReportWriter(TransmuteConfig config, MigrationPlan plan, ProjectInventory inventory,
                 MarkdownMigrationLoader.Catalog catalog) {
        this.config = config;
        this.plan = plan;
        this.inventory = inventory;
        this.catalog = catalog;
    }

    /**
     * Runs steps 9 and 12: TODO scan + report writing.
     *
     * @return todosByCategory map (may be empty)
     */
    Map<String, Long> writeReports(MigrationExecutor.ExecutionResult execResult,
                                   RepairLoopService.RepairResult repairResult,
                                   int todoStepNum, int reportStepNum, int totalSteps) throws Exception {
        var todosByCategory = scanTodos(todoStepNum, totalSteps);
        generateReport(execResult, repairResult, todosByCategory, reportStepNum, totalSteps);
        return todosByCategory;
    }

    // ── Step 9: TODO scan ─────────────────────────────────────────────────────

    private Map<String, Long> scanTodos(int stepNum, int totalSteps) {
        Out.step(stepNum, totalSteps, "Scanning for TRANSMUTE TODOs");
        var todoPattern = Pattern.compile("TRANSMUTE\\[(\\w+)\\]");
        var outputPath = Path.of(config.outputDir());

        // category → list of "  relPath:lineNum  <line>"
        var byCategory = new LinkedHashMap<String, List<String>>();

        var binaryExtensions = Set.of(".class", ".jar", ".war", ".ear",
                ".png", ".gif", ".jpg", ".jpeg", ".ico",
                ".pdf", ".zip", ".gz", ".tar", ".bin");
        try (var walk = Files.walk(outputPath)) {
            walk.filter(p -> !Files.isDirectory(p))
                .filter(p -> {
                    var name = p.getFileName().toString().toLowerCase();
                    return binaryExtensions.stream().noneMatch(name::endsWith);
                })
                .forEach(javaFile -> {
                    var rel = outputPath.relativize(javaFile).toString().replace('\\', '/');
                    try {
                        var lines = Files.readAllLines(javaFile);
                        for (int ln = 0; ln < lines.size(); ln++) {
                            var line = lines.get(ln);
                            var m = todoPattern.matcher(line);
                            if (m.find()) {
                                var cat = m.group(1);
                                byCategory.computeIfAbsent(cat, k -> new ArrayList<>())
                                          .add("  " + rel + ":" + (ln + 1) + "  " + line.stripLeading());
                            }
                        }
                    } catch (Exception ignored) {}
                });
        } catch (Exception e) {
            Out.warn("TODO scan failed: " + e.getMessage());
        }

        // Build todosByCategory map (category → count)
        var todosByCategory = byCategory.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (long) e.getValue().size(),
                        (a, b) -> a, LinkedHashMap::new));

        // Write migration-todos.txt
        if (!byCategory.isEmpty()) {
            try {
                var sb = new StringBuilder();
                sb.append("Migration TODOs\n");
                sb.append("─".repeat(15)).append("\n\n");
                for (var entry : byCategory.entrySet()) {
                    sb.append(entry.getKey()).append("  (").append(entry.getValue().size()).append(")\n");
                    for (var loc : entry.getValue()) {
                        sb.append(loc).append("\n");
                    }
                    sb.append("\n");
                }
                Files.writeString(outputPath.resolve("migration-todos.txt"), sb.toString());
            } catch (Exception e) {
                Out.warn("Could not write migration-todos.txt: " + e.getMessage());
            }
        }

        if (byCategory.isEmpty()) {
            Out.ok("No TRANSMUTE TODOs found");
        } else {
            long total = todosByCategory.values().stream().mapToLong(Long::longValue).sum();
            var parts = todosByCategory.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey())
                    .collect(Collectors.joining(", "));
            Out.info("Found " + Out.bold(total + " TRANSMUTE TODOs") + ": " + parts);
        }
        Out.rule();
        return todosByCategory;
    }

    // ── Step 12: generate report ───────────────────────────────────────────────

    private void generateReport(MigrationExecutor.ExecutionResult execResult,
                                RepairLoopService.RepairResult repairResult,
                                Map<String, Long> todosByCategory,
                                int stepNum, int totalSteps) throws Exception {
        Out.step(stepNum, totalSteps, "Generating migration report");

        boolean compileSuccess = repairResult.compileSuccess();
        boolean compileDegraded = repairResult.compileDegraded();
        int compileIterations = repairResult.compileIterations();
        boolean testSuccess = repairResult.testSuccess();
        int testIterations = repairResult.testIterations();
        var fileOutcomes = execResult.fileOutcomes();
        var allPostcheckFailures = execResult.postchecksFailures();

        var report = new LinkedHashMap<String, Object>();
        report.put("sourceDir", config.projectDir());
        report.put("outputDir", config.outputDir());
        report.put("migrationsExecuted", execResult.migrationsExecuted());
        report.put("filesChanged", execResult.changedFiles());
        report.put("dryRun", config.dryRun());
        report.put("compileOutcome", compileDegraded ? "degraded" : compileSuccess ? "success" : "failed");
        report.put("testOutcome", testSuccess ? "success" : testIterations == 0 ? "skipped" : "failed");
        report.put("compileIterations", compileIterations);
        report.put("testIterations", testIterations);
        report.put("postchecksFailures", allPostcheckFailures);
        report.put("todosByCategory", todosByCategory);
        var fileOutcomesStr = new LinkedHashMap<String, String>();
        fileOutcomes.forEach((f, o) -> fileOutcomesStr.put(f, o.name().toLowerCase()));
        report.put("fileOutcomes", fileOutcomesStr);

        var reportPath = Path.of(config.outputDir(), "migration-report.json");
        if (!config.dryRun()) {
            json.writeValue(reportPath.toFile(), report);
            Out.ok("Report written to: " + Out.DIM + reportPath + Out.RESET);
        } else {
            Out.info(Out.DIM + "[dry-run] Would write report to: " + reportPath + Out.RESET);
        }
        saveResultsToFile(execResult, repairResult);
        printConsoleReport(execResult, repairResult, todosByCategory);
        Out.rule();
    }

    private void printConsoleReport(MigrationExecutor.ExecutionResult execResult,
                                    RepairLoopService.RepairResult repairResult,
                                    Map<String, Long> todosByCategory) {
        var fileOutcomes = execResult.fileOutcomes();
        var allPostcheckFailures = execResult.postchecksFailures();
        boolean compileSuccess = repairResult.compileSuccess();
        boolean compileDegraded = repairResult.compileDegraded();
        int compileIterations = repairResult.compileIterations();
        boolean testSuccess = repairResult.testSuccess();
        int testIterations = repairResult.testIterations();

        // ── 1. Summary ────────────────────────────────────────────────────────
        Out.sectionHeader("Migration Summary",
                execResult.migrationsExecuted() + " migrations · " + execResult.changedFiles() + " files changed");

        System.out.printf("  Migrations executed  %d%n", execResult.migrationsExecuted());
        System.out.printf("  Files changed        %d%n", execResult.changedFiles());

        String compileStatus;
        if (compileDegraded) {
            compileStatus = Out.YELLOW + "⚠ degraded (" + compileIterations + " attempts)" + Out.RESET;
        } else if (compileSuccess) {
            compileStatus = Out.GREEN + "✓ success" + Out.RESET;
        } else {
            compileStatus = Out.RED + "✗ failed" + Out.RESET;
        }
        System.out.println("  Compile              " + compileStatus);

        String testStatus;
        if (testIterations == 0) {
            testStatus = Out.DIM + "— skipped" + Out.RESET;
        } else if (testSuccess) {
            testStatus = Out.GREEN + "✓ success" + Out.RESET;
        } else {
            testStatus = Out.RED + "✗ failed" + Out.RESET;
        }
        System.out.println("  Tests                " + testStatus);
        System.out.println();

        // ── 2. File Results ───────────────────────────────────────────────────
        var view = PlanView.build(plan, inventory);
        if (!view.coveredFiles().isEmpty()) {
            Out.sectionHeader("File Results", view.coveredFiles().size() + " files");
            int wFile   = 40;
            int wRecipe = 28;
            int wFeat   = 30;
            System.out.println("  "
                    + Out.DIM + PlanRenderer.padRight("File", wFile) + "  "
                    + PlanRenderer.padRight("Recipe", wRecipe) + "  "
                    + PlanRenderer.padRight("Features", wFeat) + "  "
                    + "Outcome" + Out.RESET);
            System.out.println("  "
                    + Out.DIM + "─".repeat(wFile) + "  "
                    + "─".repeat(wRecipe) + "  "
                    + "─".repeat(wFeat) + "  "
                    + "─".repeat(12) + Out.RESET);
            for (var file : view.coveredFiles()) {
                var recipes  = view.fileRecipes().getOrDefault(file, List.of());
                var features = view.fileFeatures().getOrDefault(file, List.of());
                var outcome  = fileOutcomes.get(file);
                String outcomeStr;
                if (outcome == null) {
                    outcomeStr = Out.DIM + "—" + Out.RESET;
                } else {
                    outcomeStr = switch (outcome) {
                        case CONVERTED -> Out.GREEN  + "[+]" + Out.RESET;
                        case PARTIAL   -> Out.CYAN   + "[~]" + Out.RESET;
                        case COMMENTED -> Out.YELLOW + "[!]" + Out.RESET;
                        case UNCHANGED -> Out.DIM    + "[=]" + Out.RESET;
                        case FAILED    -> Out.RED    + "[x]" + Out.RESET;
                    };
                }
                String recipePadded = recipes.isEmpty()
                        ? Out.DIM + PlanRenderer.padRight("—", wRecipe) + Out.RESET
                        : PlanRenderer.padRight(PlanRenderer.truncate(String.join(", ", recipes), wRecipe), wRecipe);
                String featStr = features.isEmpty()
                        ? Out.DIM + PlanRenderer.padRight("—", wFeat) + Out.RESET
                        : PlanRenderer.padRight(PlanRenderer.truncate(String.join(", ", features), wFeat), wFeat);
                System.out.println("  "
                        + PlanRenderer.padRight(PlanRenderer.truncate(PlanRenderer.shortPath(file), wFile), wFile) + "  "
                        + recipePadded + "  "
                        + featStr + "  "
                        + outcomeStr);
            }
            System.out.println();
        }

        // ── 3. TODOs ──────────────────────────────────────────────────────────
        if (!todosByCategory.isEmpty()) {
            long total = todosByCategory.values().stream().mapToLong(Long::longValue).sum();
            Out.sectionHeader("TRANSMUTE TODOs", total + " total");
            for (var entry : todosByCategory.entrySet()) {
                System.out.println("  " + Out.YELLOW + "[!]" + Out.RESET + "  " + entry.getKey() + "  (" + entry.getValue() + ")");
            }
            System.out.println("  " + Out.DIM + "(see migration-todos.txt)" + Out.RESET);
            System.out.println();
        }

        // ── 4. Postcheck Failures ─────────────────────────────────────────────
        if (!allPostcheckFailures.isEmpty()) {
            Out.sectionHeader("Postcheck Failures", String.valueOf(allPostcheckFailures.size()));
            for (var failure : allPostcheckFailures) {
                System.out.println("  " + Out.RED + "[!]" + Out.RESET + "  " + failure);
            }
            System.out.println();
        }
    }

    private void saveResultsToFile(MigrationExecutor.ExecutionResult execResult,
                                   RepairLoopService.RepairResult repairResult) {
        boolean compileSuccess = repairResult.compileSuccess();
        boolean compileDegraded = repairResult.compileDegraded();
        int testIterations = repairResult.testIterations();
        boolean testSuccess = repairResult.testSuccess();
        var fileOutcomes = execResult.fileOutcomes();
        var allPostcheckFailures = execResult.postchecksFailures();

        try {
            var view = PlanView.build(plan, inventory);
            var sb = new StringBuilder();
            int covered     = view.coveredFiles().size();
            int notTargeted = (int) inventory.getJavaFiles().stream()
                    .filter(f -> !view.coveredFiles().contains(f.sourceFile()))
                    .filter(f -> !f.sourceFile().replace('\\', '/').contains("/test/"))
                    .count();

            PlanRenderer.txtSection(sb, "Migration Results");
            sb.append("Generated : ").append(java.time.Instant.now()).append("\n");
            sb.append("Project   : ").append(config.projectDir()).append("\n\n");

            // Outcome summary
            long converted  = fileOutcomes.values().stream().filter(o -> o == FileOutcome.CONVERTED).count();
            long commented  = fileOutcomes.values().stream().filter(o -> o == FileOutcome.COMMENTED).count();
            long unchanged  = fileOutcomes.values().stream().filter(o -> o == FileOutcome.UNCHANGED).count();
            long failed     = fileOutcomes.values().stream().filter(o -> o == FileOutcome.FAILED).count();

            PlanRenderer.txtSection(sb, "Project Overview");
            sb.append(String.format("  Source files  : %d%n", view.totalSrc()));
            sb.append(String.format("  Test files    : %d%n", view.totalTest()));
            sb.append(String.format("  Covered       : %d%n", covered));
            sb.append(String.format("  Not targeted  : %d%n", notTargeted));
            sb.append(String.format("  Migrations    : %d%n", plan.entries().size()));
            sb.append(String.format("  Project-scoped: %d%n", view.projectScoped().size()));
            sb.append("\n");

            PlanRenderer.txtSection(sb, "Outcomes");
            if (converted > 0)  sb.append(String.format("  [+] converted  : %d%n", converted));
            if (commented > 0)  sb.append(String.format("  [!] commented  : %d%n", commented));
            if (unchanged > 0)  sb.append(String.format("  [=] unchanged  : %d%n", unchanged));
            if (failed > 0)     sb.append(String.format("  [x] failed     : %d%n", failed));
            sb.append(String.format("  compile        : %s%n",
                    compileDegraded ? "degraded" : compileSuccess ? "success" : "failed"));
            sb.append(String.format("  test           : %s%n",
                    testSuccess ? "success" : testIterations == 0 ? "skipped" : "failed"));
            sb.append("\n");

            if (!plan.entries().isEmpty()) {
                PlanRenderer.txtSection(sb, "Migration Coverage");
                int wName = plan.entries().stream()
                        .mapToInt(e -> e.migration().name().length()).max().orElse(20) + 2;
                for (var entry : plan.entries()) {
                    var am = (AiMigration) entry.migration();
                    var scope = am.skillScope() == io.transmute.migration.MigrationScope.PROJECT
                            ? "project" : entry.targetFiles().size() + " files";
                    sb.append(String.format("  %-" + wName + "s  %s%n", entry.migration().name(), scope));
                }
                sb.append("\n");
            }

            if (!allPostcheckFailures.isEmpty()) {
                PlanRenderer.txtSection(sb, "Postcheck Failures");
                for (var f : allPostcheckFailures) {
                    sb.append("  [!] ").append(f).append("\n");
                }
                sb.append("\n");
            }

            if (!view.coveredFiles().isEmpty()) {
                PlanRenderer.txtSection(sb, "File Results");
                int wFile = view.coveredFiles().stream()
                        .mapToInt(String::length).max().orElse(40) + 2;
                int wRecipe = view.fileRecipes().values().stream()
                        .mapToInt(r -> String.join(", ", r).length()).max().orElse(20) + 2;
                var hdr = PlanRenderer.padRight("File", wFile) + PlanRenderer.padRight("Recipe", wRecipe)
                        + PlanRenderer.padRight("Features", 30) + "Outcome";
                sb.append("  ").append(hdr).append("\n");
                sb.append("  ").append("─".repeat(hdr.length())).append("\n");
                for (var file : view.coveredFiles()) {
                    var recipes  = view.fileRecipes().getOrDefault(file, List.of());
                    var features = view.fileFeatures().getOrDefault(file, List.of());
                    var outcome  = fileOutcomes.get(file);
                    var outcomeSymbol = outcome == null ? "—" : switch (outcome) {
                        case CONVERTED -> "[+] converted";
                        case PARTIAL   -> "[~] partial";
                        case COMMENTED -> "[!] commented";
                        case UNCHANGED -> "[=] unchanged";
                        case FAILED    -> "[x] failed";
                    };
                    sb.append("  ")
                      .append(PlanRenderer.padRight(file, wFile))
                      .append(PlanRenderer.padRight(recipes.isEmpty() ? "—" : String.join(", ", recipes), wRecipe))
                      .append(PlanRenderer.padRight(features.isEmpty() ? "—" : String.join(", ", features), 30))
                      .append(outcomeSymbol)
                      .append("\n");
                }
                sb.append("\n");
            }

            var resultsPath = Path.of(config.outputDir()).resolve("migration-results.txt");
            Files.writeString(resultsPath, sb.toString());
            Out.info(Out.DIM + "Results saved → " + Out.RESET + resultsPath.getFileName());
        } catch (Exception e) {
            Out.warn("Could not save results file: " + e.getMessage());
        }
    }
}
