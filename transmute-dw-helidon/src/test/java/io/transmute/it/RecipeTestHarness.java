package io.transmute.it;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.transmute.agent.ModelFactory;
import io.transmute.agent.TransmuteConfig;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.migration.AiMigration;
import io.transmute.migration.FileChange;
import io.transmute.migration.MigrationResult;
import io.transmute.migration.postcheck.PostcheckRunner;
import io.transmute.tool.FileOperationsTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test harness that applies a named recipe or feature to a fixture file using the real AI model
 * and returns the transformed content plus postcheck results.
 */
class RecipeTestHarness {

    private final List<AiMigration> allMigrations;
    private final PostcheckRunner postcheckRunner = new PostcheckRunner();

    RecipeTestHarness() {
        allMigrations = new MarkdownMigrationLoader().load();
        if (allMigrations.isEmpty()) {
            throw new IllegalStateException(
                    "No migrations loaded — transmute-dw-helidon JAR may be stale or missing from local repo. "
                    + "Run: mvn install -DskipTests --also-make -pl transmute-core");
        }
    }

    /**
     * Applies the named recipe to the given file content.
     * Writes the content to a temp directory, runs the AI agent, reads back the result.
     * Returns the transformed file content.
     */
    String applyRecipe(String recipeName, String inputContent) throws Exception {
        var migration = findMigration(recipeName);

        // Derive a sensible filename from the content (first non-blank line after "package ...")
        String fileName = deriveFileName(inputContent);

        // Write input to a temp directory
        var tempDir = Files.createTempDirectory("transmute-it-");
        try {
            var inputFile = tempDir.resolve(fileName);
            Files.writeString(inputFile, inputContent);

            // Build config from environment variables (picks up TRANSMUTE_API_KEY, TRANSMUTE_MODEL_PROVIDER, etc.)
            var config = TransmuteConfig.defaults();
            ModelFactory.configure(config);
            var model = ModelFactory.create();

            // Build the AI agent using the same system prompt framing as MigrationWorkflow
            var outputDir = tempDir.toString();
            var systemPrompt = """
                    You are an expert Java developer executing a framework migration.
                    Apply ALL transformations described below. Follow every instruction exactly.
                    Do not skip any step. Do not modify anything not covered below.

                    ## %s
                    %s
                    """.formatted(migration.skillName(), migration.systemPromptSection());
            AiServices.builder(SingleFileAgent.class)
                    .chatModel(model)
                    .tools(new FileOperationsTool(outputDir))
                    .systemMessageProvider(id -> systemPrompt)
                    .build()
                    .apply("Apply all migrations to: " + fileName + "\n"
                            + "Output directory: " + outputDir + "\n"
                            + "Read the file, apply every transformation, write it back.");

            return Files.readString(inputFile);
        } finally {
            // Clean up temp directory
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        }
    }

    /**
     * Runs postchecks for the named recipe against the given output content.
     * Returns list of failure messages (empty = all pass).
     */
    List<String> runPostchecks(String recipeName, String outputContent) {
        var migration = findMigration(recipeName);
        var change = new FileChange("output", "", outputContent);
        var result = MigrationResult.success(List.of(change), List.of(), "test");
        return postcheckRunner.runMarkdownPostchecks(migration.skillPostchecks(), result);
    }

    private AiMigration findMigration(String name) {
        return allMigrations.stream()
                .filter(m -> m.skillName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No migration found with name: '" + name + "'. "
                        + "Available: " + allMigrations.stream()
                                .map(AiMigration::skillName)
                                .toList()));
    }

    /**
     * Derives a .java filename from the class declaration in the content,
     * falling back to "Output.java" if no public class can be found.
     */
    private String deriveFileName(String content) {
        for (var line : content.split("\\R")) {
            var trimmed = line.trim();
            if (trimmed.startsWith("public class ")
                    || trimmed.startsWith("public interface ")
                    || trimmed.startsWith("public enum ")
                    || trimmed.startsWith("public record ")) {
                var parts = trimmed.split("\\s+");
                if (parts.length >= 3) {
                    var name = parts[2];
                    // Strip generics or extends/implements
                    int angle = name.indexOf('<');
                    if (angle > 0) name = name.substring(0, angle);
                    int space = name.indexOf(' ');
                    if (space > 0) name = name.substring(0, space);
                    if (!name.isBlank()) {
                        return name + ".java";
                    }
                }
            }
        }
        return "Output.java";
    }

    /** Single-turn AI service used to apply recipes/features to one file. */
    private interface SingleFileAgent {
        @UserMessage("{{msg}}")
        String apply(@V("msg") String msg);
    }
}
