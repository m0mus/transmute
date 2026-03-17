package io.transmute.migration;

import io.transmute.inventory.ProjectInventory;

import java.util.List;

/**
 * Planning interface for all migrations.
 *
 * <p>All concrete migrations are markdown-based ({@link AiMigration}). This interface
 * exists as the common list element type for the planner and workflow.
 */
public interface Migration {

    /** Human-readable name used in logs, reports, and {@code after} resolution. */
    String name();

    /** Execution order — lower values run first. Defaults to 50. */
    default int order() { return 50; }

    /** Names of migrations that must complete before this one. */
    default List<String> after() { return List.of(); }
}
