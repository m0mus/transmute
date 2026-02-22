package io.transmute.catalog;

import io.transmute.skill.MigrationSkill;

import java.util.List;

/**
 * An ordered list of skill executions produced by {@link MigrationPlanner}.
 */
public record MigrationPlan(List<SkillExecutionEntry> entries) {

    public MigrationPlan {
        entries = List.copyOf(entries);
    }

    /**
     * One entry in the plan: the skill to run, the files it targets, and metadata.
     */
    public record SkillExecutionEntry(
            MigrationSkill skill,
            List<String> targetFiles,
            SkillConfidence confidence,
            boolean aiInvolved
    ) {
        public SkillExecutionEntry {
            targetFiles = List.copyOf(targetFiles);
        }
    }
}
