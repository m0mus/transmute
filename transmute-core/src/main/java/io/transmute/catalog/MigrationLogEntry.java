package io.transmute.catalog;

import io.transmute.skill.MigrationSkill;

/**
 * An immutable log entry recording what a skill did to a file.
 */
public record MigrationLogEntry(
        Class<? extends MigrationSkill> skill,
        String file,
        LogStatus status
) {}
