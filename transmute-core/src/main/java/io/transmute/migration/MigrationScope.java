package io.transmute.migration;

/**
 * Determines how a Java {@link Migration} is executed by the planner.
 *
 * <ul>
 *   <li>{@link #FILE} — the migration runs once per matching file identified by
 *       {@link Migration#isTriggered}; the migration receives the inventory
 *       and decides which files to process itself.</li>
 *   <li>{@link #PROJECT} — the migration runs once for the whole project.</li>
 * </ul>
 *
 * <p>For AI migrations ({@link AiMigration}) scope is derived automatically from the
 * trigger declarations: file-level triggers (imports/annotations/superTypes) imply FILE
 * scope; project-level triggers (signals/files/compileErrors) imply PROJECT scope.
 */
public enum MigrationScope {
    FILE,
    PROJECT
}
