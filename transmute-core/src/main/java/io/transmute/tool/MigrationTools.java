package io.transmute.tool;

import java.util.List;
import java.util.Set;

/**
 * Registry of core migration tool instances.
 * The {@code outputDir} and {@code activeProfiles} are injected at construction time.
 */
public class MigrationTools {

    private final String outputDir;
    private final List<String> activeProfiles;

    public MigrationTools(String outputDir, List<String> activeProfiles) {
        this.outputDir = outputDir;
        this.activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
    }

    public List<Object> all() {
        return List.of(
                new FileOperationsTool(outputDir),
                new ValidationTool(),
                new CompileProjectTool(activeProfiles),
                new RunTestsTool(activeProfiles),
                new CopyProjectTool()
        );
    }

    private static final Set<Class<?>> WORKFLOW_ONLY_TOOLS = Set.of(
            CopyProjectTool.class,
            CompileProjectTool.class,
            RunTestsTool.class
    );

    /**
     * Tools suitable for AI agents performing code edits
     * (excludes workflow-orchestration tools).
     */
    public List<Object> codeEditTools() {
        return all().stream()
                .filter(t -> !WORKFLOW_ONLY_TOOLS.contains(t.getClass()))
                .toList();
    }
}
