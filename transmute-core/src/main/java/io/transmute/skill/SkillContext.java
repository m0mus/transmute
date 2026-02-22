package io.transmute.skill;

import dev.langchain4j.model.chat.ChatModel;
import io.transmute.catalog.MigrationLog;
import io.transmute.catalog.MigrationLogEntry;
import io.transmute.catalog.ProjectState;
import io.transmute.inventory.ProjectInventory;

import java.util.List;
import java.util.Optional;

/**
 * Provides a skill with everything it needs to execute:
 * the project inventory, target file list, project state, and AI model.
 */
public class SkillContext {

    private final ProjectInventory inventory;
    private final List<String> targetFiles;
    private final ProjectState projectState;
    private final List<String> compileErrors;
    private final ChatModel model;
    private final Workspace workspace;
    private final MigrationLog migrationLog;

    public SkillContext(
            ProjectInventory inventory,
            List<String> targetFiles,
            ProjectState projectState,
            List<String> compileErrors,
            ChatModel model,
            Workspace workspace,
            MigrationLog migrationLog) {
        this.inventory = inventory;
        this.targetFiles = List.copyOf(targetFiles);
        this.projectState = projectState;
        this.compileErrors = List.copyOf(compileErrors);
        this.model = model;
        this.workspace = workspace;
        this.migrationLog = migrationLog;
    }

    public ProjectInventory inventory() {
        return inventory;
    }

    /**
     * Pre-resolved list of files this skill should process (FILE-scope skills).
     * PROJECT-scope skills should use {@link #inventory()} instead.
     */
    public List<String> targetFiles() {
        return targetFiles;
    }

    public ProjectState projectState() {
        return projectState;
    }

    public List<String> compileErrors() {
        return compileErrors;
    }

    /**
     * The AI chat model. May be {@code null} for non-AI skills.
     */
    public ChatModel model() {
        return model;
    }

    public Workspace workspace() {
        return workspace;
    }

    /**
     * Retrieves a typed value from the shared {@link ProjectState}.
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        return projectState.get(key, type);
    }

    /**
     * Returns the execution history for a specific output file.
     */
    public List<MigrationLogEntry> executionHistory(String outputFile) {
        return migrationLog.history(outputFile);
    }
}
