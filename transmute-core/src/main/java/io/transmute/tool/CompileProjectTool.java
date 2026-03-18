package io.transmute.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
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
            var goals = buildGoals("compile", "-q");
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
            return new CompileResult(result.success(), filtered);
        } catch (Exception e) {
            return new CompileResult(false, "Failed to run mvn compile: " + e.getMessage());
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
