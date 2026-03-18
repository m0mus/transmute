package io.transmute.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Runs Maven tests on the output project to verify test success.
 */
public class RunTestsTool {

    public record TestResult(boolean success, String output) {}

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> activeProfiles;

    public RunTestsTool() {
        this(List.of());
    }

    public RunTestsTool(List<String> activeProfiles) {
        this.activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
    }

    @Tool("Run 'mvn test' on the migrated project directory. " +
          "Returns success status and any test failure output.")
    public String runTests(
            @P("Absolute path to the output project directory") String outputDir) {
        var result = runMvnTest(outputDir);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("success", result.success());
        payload.put("output", result.output());
        return toJson(payload);
    }

    public TestResult runMvnTest(String outputDir) {
        try {
            var goals = buildGoals("test");
            ToolLog.log("run_tests " + outputDir);
            var result = MavenRunner.runWithExitCode(Path.of(outputDir), goals, false);
            return new TestResult(result.success(), result.output());
        } catch (Exception e) {
            return new TestResult(false, "Failed to run mvn test: " + e.getMessage());
        }
    }

    private List<String> buildGoals(String... goals) {
        var list = new java.util.ArrayList<String>(List.of(goals));
        if (!activeProfiles.isEmpty()) {
            list.add("-P");
            list.add(String.join(",", activeProfiles));
        }
        return list;
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"success\":false,\"output\":\"Failed to serialize result\"}";
        }
    }
}
