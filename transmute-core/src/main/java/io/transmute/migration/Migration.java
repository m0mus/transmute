package io.transmute.migration;

/**
 * Planning interface for all migrations.
 *
 * <p>All concrete migrations are markdown-based ({@link AiMigration}). This interface
 * exists as the common list element type for the planner and workflow.
 */
public interface Migration {

    /** Human-readable name used in logs and reports. */
    String name();

    /** Execution order — lower values run first. Defaults to 50. */
    default int order() { return 50; }
}
