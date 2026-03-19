package io.transmute.agent.workflow;

import io.transmute.agent.TransmuteConfig;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.migration.Workspace;

/**
 * Runs the Transmute migration pipeline as plain sequential Java.
 *
 * <p>Pipeline (12 steps):
 * <ol>
 *   <li>Copy project to output dir</li>
 *   <li>Scan inventory</li>
 *   <li>Analyze project (AI produces a structured summary for recipe context)</li>
 *   <li>Discover migrations</li>
 *   <li>Build migration plan</li>
 *   <li>Human approval (if !autoApprove)</li>
 *   <li>Execute migrations (Java migrations + AI recipes/features)</li>
 *   <li>Human review gate (if !autoApprove)</li>
 *   <li>Scan TODOs (collect TRANSMUTE markers)</li>
 *   <li>Compile-fix loop (max 5 iterations)</li>
 *   <li>Test-fix loop (max 5 iterations)</li>
 *   <li>Generate report</li>
 * </ol>
 *
 * <p>AI is used in steps 3 (project analysis), 7 (recipe/feature agent calls),
 * and 10–11 (fix agents).
 */
public class MigrationWorkflow {

    private static final int TOTAL_STEPS = 12;

    private final TransmuteConfig config;
    private final MarkdownMigrationLoader loader = new MarkdownMigrationLoader();
    private final MarkdownMigrationLoader.Hints hints = loader.loadHints();
    private final MarkdownMigrationLoader.Catalog catalog = loader.loadCatalog();

    public MigrationWorkflow(TransmuteConfig config) {
        this.config = config;
    }

    public void run() throws Exception {
        printBanner();
        printConfig();

        copyProject();                                                    // step 1

        var inventory      = new InventoryService().scan(config, 2, TOTAL_STEPS);
        var projectSummary = new InventoryService().analyze(inventory, config, 3, TOTAL_STEPS);

        var planningService = new PlanningService(loader);
        var migrations      = planningService.discoverMigrations(4, TOTAL_STEPS);

        Out.step(5, TOTAL_STEPS, "Building migration plan");              // step 5
        var plan        = planningService.buildPlan(migrations, inventory);
        var planRenderer = new PlanRenderer(config, plan, inventory, migrations, catalog);
        planRenderer.printAndSave();
        Out.rule();

        approvePlan();                                                    // step 6

        var execResult = new MigrationExecutor(config, projectSummary)
                .execute(plan, new Workspace(config.projectDir(), config.outputDir(), config.dryRun()),
                        7, TOTAL_STEPS);                                 // step 7

        reviewGate();                                                     // step 8

        var repairResult = new RepairLoopService(config, projectSummary,
                planRenderer.buildCatalogHints(), hints)
                .repair(execResult, 10, 11, TOTAL_STEPS);                // steps 10–11

        new ReportWriter(config, plan, inventory, catalog)
                .writeReports(execResult, repairResult, 9, 12, TOTAL_STEPS); // steps 9, 12

        printDone();
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private void copyProject() {
        Out.step(1, TOTAL_STEPS, "Copying project to output directory");
        var result = new io.transmute.tool.CopyProjectTool().copyProjectResult(config.projectDir(), config.outputDir());
        if (result.success()) {
            Out.ok(result.message());
        } else {
            Out.error(result.message());
            throw new RuntimeException("Copy failed: " + result.message());
        }
        Out.rule();
    }

    private void approvePlan() {
        Out.step(6, TOTAL_STEPS, "Plan approval");
        if (config.autoApprove()) {
            Out.info(Out.DIM + "auto-approve" + Out.RESET);
            Out.rule();
            return;
        }
        String answer = readUserInput("\n  Proceed with migration? (yes/no): ");
        if (answer == null || !answer.trim().toLowerCase().startsWith("y")) {
            throw new RuntimeException("Migration aborted by user.");
        }
        Out.rule();
    }

    private void reviewGate() {
        Out.step(8, TOTAL_STEPS, "Review changes");
        if (config.autoApprove()) {
            Out.info(Out.DIM + "auto-approve" + Out.RESET);
            Out.rule();
            return;
        }
        System.out.println("  Output: " + config.outputDir());
        String answer = readUserInput("\n  Approve and proceed to compile+test? (yes/no): ");
        if (answer == null || !answer.trim().toLowerCase().startsWith("y")) {
            throw new RuntimeException("Migration stopped at review step.");
        }
        Out.rule();
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    private static void printBanner() {
        // ASCII art generated with "slant" style lettering
        String c = Out.CYAN + Out.BOLD;
        String r = Out.RESET;
        String d = Out.DIM;
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
        var d = Out.DIM;
        var r = Out.RESET;
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

    private void printDone() {
        System.out.println();
        System.out.println(Out.BOLD + Out.GREEN + "Migration complete." + Out.RESET
                + Out.DIM + "  Output: " + config.outputDir() + Out.RESET);
    }

    private String resolvedModelId() {
        return switch (config.modelProvider()) {
            case "oci-genai" -> config.modelId("cohere.command-r-plus");
            case "openai"    -> config.modelId("gpt-4o");
            case "ollama"    -> config.modelId("llama3.3");
            default          -> config.modelId();
        };
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
}
