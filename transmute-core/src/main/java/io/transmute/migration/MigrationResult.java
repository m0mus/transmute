package io.transmute.migration;

import java.util.List;

/**
 * The outcome of executing a {@link Migration}.
 */
public record MigrationResult(
        List<FileChange> changes,
        List<String> todos,
        boolean success,
        String message
) {

    public MigrationResult {
        changes = List.copyOf(changes);
        todos = List.copyOf(todos);
    }

    public static MigrationResult noChange() {
        return new MigrationResult(List.of(), List.of(), true, "no change");
    }

    public static MigrationResult failure(String msg) {
        return new MigrationResult(List.of(), List.of(), false, msg);
    }

    public static MigrationResult success(List<FileChange> changes, List<String> todos, String message) {
        return new MigrationResult(changes, todos, true, message);
    }
}
