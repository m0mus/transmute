package io.transmute.catalog;

import io.transmute.migration.Migration;

/**
 * An immutable log entry recording what a skill did to a file.
 */
public record MigrationLogEntry(
        Class<? extends Migration> migration,
        String file,
        LogStatus status
) {}
