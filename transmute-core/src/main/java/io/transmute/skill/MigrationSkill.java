package io.transmute.skill;

/**
 * Core interface for all migration skills.
 *
 * <p>Implementations must also be annotated with
 * {@link io.transmute.skill.annotation.Skill} for discovery and ordering.
 */
public interface MigrationSkill {

    /**
     * Apply this skill to the project or file(s) described in {@code ctx}.
     *
     * @param ctx the skill execution context
     * @return the result of applying the skill
     * @throws Exception on unrecoverable failure
     */
    SkillResult apply(SkillContext ctx) throws Exception;

    /**
     * Human-readable name used in logs and reports.
     * Defaults to the simple class name.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
