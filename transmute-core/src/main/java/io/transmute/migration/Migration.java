package io.transmute.migration;

import io.transmute.inventory.ProjectInventory;

import java.util.List;

/**
 * Core interface for all migrations — both Java and AI-driven.
 *
 * <p>Java implementations are discovered by scanning for classes that implement this
 * interface. No annotations are required. Override the default methods to control
 * ordering, dependencies, and triggering.
 */
public interface Migration {

    /**
     * Apply this migration.
     *
     * <p>The migration has full access to the project inventory via {@code ctx.inventory()}
     * and should determine which files to process itself.
     *
     * @param ctx the migration execution context
     * @return the result of applying the migration
     * @throws Exception on unrecoverable failure
     */
    MigrationResult apply(MigrationContext ctx) throws Exception;

    /** Human-readable name used in logs, reports, and {@code after} resolution. Defaults to the simple class name. */
    default String name() { return getClass().getSimpleName(); }

    /** Execution order — lower values run first. Defaults to 50. */
    default int order() { return 50; }

    /**
     * Names of migrations that must complete before this one.
     * Matches by {@link #name()}, works across both Java and markdown migrations.
     */
    default List<String> after() { return List.of(); }

    /**
     * Returns {@code true} if this migration should run against the given inventory.
     * Override to add triggering logic (e.g., check for specific dependencies or signals).
     * Defaults to always running.
     */
    default boolean isTriggered(ProjectInventory inventory) { return true; }
}
