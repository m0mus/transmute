package io.transmute.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

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
            var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            var cmd = buildCommand(isWindows, "test");

            ToolLog.log("run_tests " + outputDir);
            var pb = new ProcessBuilder(cmd)
                    .directory(Path.of(outputDir).toFile())
                    .redirectErrorStream(true);

            var process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            var exitCode = process.waitFor();
            return new TestResult(exitCode == 0, output);
        } catch (Exception e) {
            return new TestResult(false, "Failed to run mvn test: " + e.getMessage());
        }
    }

    private List<String> buildCommand(boolean isWindows, String... goals) {
        var cmd = new ArrayList<String>();
        if (isWindows) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add("mvn");
        } else {
            cmd.add("mvn");
        }
        for (var goal : goals) {
            cmd.add(goal);
        }
        if (!activeProfiles.isEmpty()) {
            cmd.add("-P");
            cmd.add(String.join(",", activeProfiles));
        }
        return cmd;
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"success\":false,\"output\":\"Failed to serialize result\"}";
        }
    }
}
