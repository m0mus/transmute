package io.transmute.compile;

import io.transmute.skill.MigrationSkill;

import java.util.Optional;

/**
 * Full analysis of a single {@link CompileError}.
 */
public record ErrorAnalysis(
        CompileError error,
        ErrorClass errorClass,
        Optional<Class<? extends MigrationSkill>> suggestedSkill
) {}
