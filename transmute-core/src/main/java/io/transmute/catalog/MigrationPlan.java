package io.transmute.catalog;

import io.transmute.migration.Migration;

import java.util.List;
import java.util.Map;

/**
 * An ordered list of migration executions produced by {@link MigrationPlanner}.
 */
public record MigrationPlan(List<MigrationExecutionEntry> entries) {

    public MigrationPlan {
        entries = List.copyOf(entries);
    }

    /**
     * One entry in the plan: the migration to run and the files it targets.
     */
    public record MigrationExecutionEntry(
            Migration migration,
            List<String> targetFiles
    ) {
        public MigrationExecutionEntry {
            targetFiles = List.copyOf(targetFiles);
        }
    }
}
