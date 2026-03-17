package io.transmute.migration;

/**
 * Determines how a {@link Migration} is executed by the planner.
 *
 * <ul>
 *   <li>{@link #FILE} - the migration runs once per matching target file path.</li>
 *   <li>{@link #PROJECT} - the migration runs once for the whole project.</li>
 * </ul>
 *
 * <p>For AI migrations ({@link AiMigration}) scope is derived from triggers:
 * any {@code imports}, {@code annotations}, {@code superTypes}, or {@code files}
 * condition implies FILE scope; otherwise PROJECT scope.
 */
public enum MigrationScope {
    FILE,
    PROJECT
}
