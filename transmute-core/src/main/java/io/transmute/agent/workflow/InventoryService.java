package io.transmute.agent.workflow;

import dev.langchain4j.service.AiServices;
import io.transmute.agent.ModelFactory;
import io.transmute.agent.TransmuteConfig;
import io.transmute.agent.agent.ProjectAnalysisAgent;
import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Steps 2–3: scan project → {@link ProjectInventory}; analyze → {@code String projectSummary}.
 */
class InventoryService {

    ProjectInventory scan(TransmuteConfig config, int stepNum, int totalSteps) {
        Out.step(stepNum, totalSteps, "Scanning project inventory");
        var inventory = new io.transmute.inventory.JavaProjectScanner().scan(config.projectDir());
        Out.info("Scanned " + Out.bold(inventory.getJavaFiles().size() + " Java files"));
        Out.rule();
        return inventory;
    }

    String analyze(ProjectInventory inventory, TransmuteConfig config, int stepNum, int totalSteps) {
        Out.step(stepNum, totalSteps, "Analyzing project structure");
        String projectSummary;
        try {
            var inventorySummary = buildInventorySummary(inventory);
            var keyFileContents = readKeyFiles(inventory, config);
            projectSummary = AiServices.builder(ProjectAnalysisAgent.class)
                    .chatModel(ModelFactory.create())
                    .build()
                    .analyze(inventorySummary, keyFileContents);
            Out.ok("Project analysis complete (" + projectSummary.lines().count() + " lines)");
        } catch (Exception e) {
            Out.warn("Project analysis failed (continuing without): " + e.getMessage());
            projectSummary = "";
        }
        Out.rule();
        return projectSummary;
    }

    private String buildInventorySummary(ProjectInventory inventory) {
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

    private String readKeyFiles(ProjectInventory inventory, TransmuteConfig config) {
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
}
