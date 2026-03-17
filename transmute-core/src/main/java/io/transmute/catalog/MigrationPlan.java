package io.transmute.catalog;

import io.transmute.migration.Migration;

import java.util.List;

/**
 * An ordered list of migration executions produced by {@link MigrationPlanner}.
 */
public record MigrationPlan(List<MigrationExecutionEntry> entries) {

    public MigrationPlan {
        entries = List.copyOf(entries);
    }

    /**
     * One entry in the plan: the migration to run, the files it targets, and metadata.
     */
    public record MigrationExecutionEntry(
            Migration migration,
            List<String> targetFiles,
            MigrationConfidence confidence,
            boolean aiInvolved
    ) {
        public MigrationExecutionEntry {
            targetFiles = List.copyOf(targetFiles);
        }
    }
}
