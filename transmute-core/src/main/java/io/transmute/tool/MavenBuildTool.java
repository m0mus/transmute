package io.transmute.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unified Maven build tool — exposes compile and test execution to AI agents.
 */
public class MavenBuildTool {

    public record BuildResult(boolean success, String output) {}

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> activeProfiles;

    public MavenBuildTool() {
        this(List.of());
    }

    public MavenBuildTool(List<String> activeProfiles) {
        this.activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
    }

    @Tool("Run 'mvn clean compile' on the migrated project directory. " +
          "Returns success status and any compilation error output.")
    public String compile(
            @P("Absolute path to the output project directory") String outputDir) {
        var result = runCompile(outputDir);
        return toJson(result);
    }

    @Tool("Run 'mvn clean test' on the migrated project directory. " +
          "Returns success status and any test failure output.")
    public String runTests(
            @P("Absolute path to the output project directory") String outputDir) {
        var result = runMvnTest(outputDir);
        return toJson(result);
    }

    public BuildResult runCompile(String outputDir) {
        try {
            var goals = buildGoals("clean", "compile", "-q");
            ToolLog.log("compile_project " + outputDir);
            var result = MavenRunner.runWithExitCode(Path.of(outputDir), goals, false);
            var filtered = result.output().lines()
                    .filter(l -> !l.startsWith("WARNING:")
                            && !l.startsWith("[WARNING]")
                            && !l.contains("To see the full stack trace")
                            && !l.contains("Re-run Maven using the -X switch")
                            && !l.contains("For more information about the errors")
                            && !l.matches("\\[ERROR\\]\\s*"))
                    .collect(Collectors.joining("\n"));
            return new BuildResult(result.success(), filtered);
        } catch (Exception e) {
            return new BuildResult(false, "Failed to run mvn compile: " + e.getMessage());
        }
    }

    public BuildResult runMvnTest(String outputDir) {
        try {
            var goals = buildGoals("clean", "test");
            ToolLog.log("run_tests " + outputDir);
            var result = MavenRunner.runWithExitCode(Path.of(outputDir), goals, false);
            var filtered = result.output().lines()
                    .filter(l -> !l.startsWith("WARNING:")
                            && !l.startsWith("[WARNING]")
                            && !l.contains("Downloading from")
                            && !l.contains("Downloaded from")
                            && !l.contains("Progress (")
                            && !l.matches("\\[ERROR\\]\\s*"))
                    .collect(Collectors.joining("\n"));
            return new BuildResult(result.success(), filtered);
        } catch (Exception e) {
            return new BuildResult(false, "Failed to run mvn test: " + e.getMessage());
        }
    }

    private List<String> buildGoals(String... goals) {
        var list = new ArrayList<String>(List.of(goals));
        if (!activeProfiles.isEmpty()) {
            list.add("-P");
            list.add(String.join(",", activeProfiles));
        }
        return list;
    }

    private String toJson(BuildResult result) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("success", result.success());
            payload.put("output", result.output());
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"success\":false,\"output\":\"Failed to serialize result\"}";
        }
    }
}
