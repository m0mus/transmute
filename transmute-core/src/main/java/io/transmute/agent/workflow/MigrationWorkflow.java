package io.transmute.agent.workflow;

import io.transmute.agent.TransmuteConfig;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.migration.Workspace;

/**
 * Runs the Transmute migration pipeline as plain sequential Java.
 *
 * <p>Pipeline (10 steps):
 * <ol>
 *   <li>Copy project to output dir</li>
 *   <li>Scan inventory</li>
 *   <li>Analyze project (AI produces a structured summary for recipe context)</li>
 *   <li>Discover migrations</li>
 *   <li>Build migration plan</li>
 *   <li>Execute migrations (Java migrations + AI recipes/features)</li>
 *   <li>Scan TODOs (collect TRANSMUTE markers)</li>
 *   <li>Compile-fix loop (max 5 iterations)</li>
 *   <li>Test-fix loop (max 5 iterations)</li>
 *   <li>Generate report</li>
 * </ol>
 *
 * <p>AI is used in steps 3 (project analysis), 6 (recipe/feature agent calls),
 * and 8–9 (fix agents).
 */
public class MigrationWorkflow {

    private static final int TOTAL_STEPS = 10;

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

        var execResult = new MigrationExecutor(config, projectSummary)
                .execute(plan, new Workspace(config.projectDir(), config.outputDir(), config.dryRun()),
                        6, TOTAL_STEPS);                                 // step 6

        var repairResult = new RepairLoopService(config, projectSummary,
                planRenderer.buildCatalogHints(), hints)
                .repair(execResult, 8, 9, TOTAL_STEPS);                 // steps 8–9

        new ReportWriter(config, plan, inventory, catalog)
                .writeReports(execResult, repairResult, 7, 10, TOTAL_STEPS); // steps 7, 10

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

}
