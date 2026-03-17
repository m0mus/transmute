package io.transmute.migration;

import dev.langchain4j.model.chat.ChatModel;
import io.transmute.catalog.MigrationLog;
import io.transmute.catalog.MigrationLogEntry;
import io.transmute.catalog.ProjectState;
import io.transmute.inventory.ProjectInventory;

import java.util.List;
import java.util.Optional;

/**
 * Provides a migration with everything it needs to execute.
 *
 * <p>Java migrations access the full project via {@link #inventory()} and decide
 * which files to process themselves. AI migrations use this context indirectly
 * through the workflow's agent invocation.
 */
public class MigrationContext {

    private final ProjectInventory inventory;
    private final ProjectState projectState;
    private final List<String> compileErrors;
    private final ChatModel model;
    private final Workspace workspace;
    private final MigrationLog migrationLog;

    public MigrationContext(
            ProjectInventory inventory,
            ProjectState projectState,
            List<String> compileErrors,
            ChatModel model,
            Workspace workspace,
            MigrationLog migrationLog) {
        this.inventory = inventory;
        this.projectState = projectState;
        this.compileErrors = List.copyOf(compileErrors);
        this.model = model;
        this.workspace = workspace;
        this.migrationLog = migrationLog;
    }

    public ProjectInventory inventory() { return inventory; }

    public ProjectState projectState() { return projectState; }

    public List<String> compileErrors() { return compileErrors; }

    /** The AI chat model. May be {@code null} for non-AI migrations. */
    public ChatModel model() { return model; }

    public Workspace workspace() { return workspace; }

    /** Retrieves a typed value from the shared {@link ProjectState}. */
    public <T> Optional<T> get(String key, Class<T> type) {
        return projectState.get(key, type);
    }

    /** Returns the execution history for a specific output file. */
    public List<MigrationLogEntry> executionHistory(String outputFile) {
        return migrationLog.history(outputFile);
    }
}
