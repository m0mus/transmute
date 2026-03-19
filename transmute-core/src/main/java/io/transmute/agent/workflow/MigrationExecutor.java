package io.transmute.agent.workflow;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.transmute.agent.ModelFactory;
import io.transmute.agent.TransmuteConfig;
import io.transmute.catalog.MigrationPlan;
import io.transmute.migration.AiMigration;
import io.transmute.migration.FileChange;
import io.transmute.migration.FileOutcome;
import io.transmute.migration.MigrationResult;
import io.transmute.migration.MigrationScope;
import io.transmute.migration.Workspace;
import io.transmute.migration.postcheck.PostcheckRunner;
import io.transmute.tool.FileOperationsTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Step 7: per-file + project-scoped AI execution.
 */
class MigrationExecutor {

    private final TransmuteConfig config;
    private final PromptBuilder promptBuilder;

    MigrationExecutor(TransmuteConfig config, String projectSummary) {
        this.config = config;
        this.promptBuilder = new PromptBuilder(projectSummary);
    }

    ExecutionResult execute(MigrationPlan plan, Workspace workspace, int stepNum, int totalSteps) {
        Out.step(stepNum, totalSteps, "Executing migrations");

        long migrationsExecuted = 0;
        long changedFiles = 0;
        var fileOutcomes = new LinkedHashMap<String, FileOutcome>();
        var allPostcheckFailures = new ArrayList<String>();

        if (plan.entries().isEmpty()) {
            Out.warn("No migrations to execute.");
            Out.rule();
            return new ExecutionResult(0, 0, fileOutcomes, allPostcheckFailures);
        }

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
            var result = applyToFile(e.getKey(), e.getValue(), workspace, model, postcheckRunner,
                    fileOutcomes, allPostcheckFailures);
            if (result.success()) {
                migrationsExecuted += e.getValue().size();
            }
            if (result.changed()) {
                changedFiles++;
            }
        }

        Out.ok("Migration execution complete");
        Out.rule();
        return new ExecutionResult(migrationsExecuted, changedFiles, fileOutcomes, allPostcheckFailures);
    }

    // ── Result record ─────────────────────────────────────────────────────────

    record ExecutionResult(
            long migrationsExecuted, long changedFiles,
            Map<String, FileOutcome> fileOutcomes,
            List<String> postchecksFailures) {}

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Applies a project-scoped recipe/feature once for the whole output directory.
     */
    private boolean applyToProject(
            AiMigration aiMigration,
            Workspace workspace,
            dev.langchain4j.model.chat.ChatModel model) {

        Out.migrationProject(aiMigration.skillName());

        if (config.dryRun()) {
            Out.dryRunSkip();
            return false;
        }
        try {
            AiServices.builder(SingleFileAgent.class)
                    .chatModel(model)
                    .tools(new FileOperationsTool(workspace.outputDir()))
                    .systemMessageProvider(id -> promptBuilder.buildProjectScoped(aiMigration))
                    .build()
                    .apply("Apply the migration to the project at: " + workspace.outputDir()
                            + "\nUse paths relative to the output directory."
                            + "\nAfter completing changes, append a summary to " + PromptBuilder.JOURNAL_FILE
                            + " using append_file (what you changed, key decisions, edge cases).");
            return true;
        } catch (Exception e) {
            Out.error("Agent failed for project migration " + aiMigration.skillName() + ": " + e.getMessage());
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
            PostcheckRunner postcheckRunner,
            Map<String, FileOutcome> fileOutcomes,
            List<String> allPostcheckFailures) {

        var sourceDirAbs = Path.of(workspace.sourceDir()).toAbsolutePath().normalize();
        var relPath = sourceDirAbs.relativize(sourceDirAbs.resolve(sourceFile).normalize()).toString();
        var outputDir = workspace.outputDir();

        String beforeContent;
        try {
            beforeContent = Files.readString(Path.of(outputDir).resolve(relPath));
        } catch (Exception e) {
            Out.error("Cannot read " + relPath + ": " + e.getMessage());
            fileOutcomes.put(sourceFile, FileOutcome.FAILED);
            return ApplyResult.failed();
        }

        var migrationNames = aiMigrations.stream().map(AiMigration::skillName).toList();
        Out.migrationFile(Path.of(relPath).getFileName().toString(), migrationNames);

        if (config.dryRun()) {
            Out.dryRunSkip();
            fileOutcomes.put(sourceFile, FileOutcome.FAILED);
            return ApplyResult.failed();
        }

        var combinedPrompt = promptBuilder.buildCombined(aiMigrations);
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
            Out.error("Agent failed for " + relPath + ": " + e.getMessage());
            fileOutcomes.put(sourceFile, FileOutcome.FAILED);
            return ApplyResult.failed();
        }

        String afterContent;
        try {
            afterContent = Files.readString(Path.of(outputDir).resolve(relPath));
        } catch (Exception e) {
            Out.error("Cannot read result for " + relPath + ": " + e.getMessage());
            fileOutcomes.put(sourceFile, FileOutcome.FAILED);
            return ApplyResult.failed();
        }

        var change = FileChange.of(sourceFile, beforeContent, afterContent);
        fileOutcomes.put(sourceFile, change.outcome());
        var result = MigrationResult.success(List.of(change), List.of(), "migration applied");

        for (var aiMigration : aiMigrations) {
            var failures = postcheckRunner.runMarkdownPostchecks(aiMigration.skillPostchecks(), result);
            if (!failures.isEmpty()) {
                Out.warn("Postcheck failures (" + aiMigration.skillName() + ") for " + relPath + ":");
                failures.forEach(f -> System.out.println("      " + Out.YELLOW + f + Out.RESET));
                failures.stream()
                        .map(f -> "[" + aiMigration.skillName() + "] " + f)
                        .forEach(allPostcheckFailures::add);
            }
        }
        return new ApplyResult(true, change.isChanged());
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
}
