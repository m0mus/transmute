package io.transmute.skill.annotation;

/**
 * Determines how the migration planner resolves target files for a skill.
 *
 * <ul>
 *   <li>{@link #FILE} — the planner pre-resolves a list of matching files via
 *       trigger predicates; the skill receives them in
 *       {@link io.transmute.skill.SkillContext#targetFiles()}.</li>
 *   <li>{@link #PROJECT} — the skill operates on the entire project; the planner
 *       passes an empty targetFiles list and the skill uses
 *       {@link io.transmute.skill.SkillContext#inventory()} directly.</li>
 * </ul>
 */
public enum SkillScope {
    FILE,
    PROJECT
}
