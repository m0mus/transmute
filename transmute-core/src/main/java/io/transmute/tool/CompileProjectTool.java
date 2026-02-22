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
 * Runs Maven compile on the output project to verify compilation success.
 */
public class CompileProjectTool {

    public record CompileResult(boolean success, String errors) {}

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> activeProfiles;

    public CompileProjectTool() {
        this(List.of());
    }

    public CompileProjectTool(List<String> activeProfiles) {
        this.activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
    }

    @Tool("Run 'mvn compile' on the migrated project directory. " +
          "Returns success status and any compilation error output.")
    public String compile(
            @P("Absolute path to the output project directory") String outputDir) {
        var result = runCompile(outputDir);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("success", result.success());
        payload.put("output", result.errors());
        return toJson(payload);
    }

    public CompileResult runCompile(String outputDir) {
        try {
            var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            var cmd = buildCommand(isWindows, "compile", "-q");

            ToolLog.log("compile_project " + outputDir);
            var pb = new ProcessBuilder(cmd)
                    .directory(Path.of(outputDir).toFile())
                    .redirectErrorStream(true);

            var process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            var exitCode = process.waitFor();
            return new CompileResult(exitCode == 0, output);
        } catch (Exception e) {
            return new CompileResult(false, "Failed to run mvn compile: " + e.getMessage());
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
