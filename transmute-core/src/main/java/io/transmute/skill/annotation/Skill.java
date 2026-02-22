package io.transmute.skill.annotation;

import io.transmute.skill.MigrationSkill;

import java.lang.annotation.*;

/**
 * Marks a {@link MigrationSkill} implementation for discovery and ordering.
 *
 * <p>Discovered by {@link io.transmute.catalog.SkillDiscovery} via ClassGraph.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Skill {

    /** Human-readable name; defaults to the simple class name. */
    String value() default "";

    /**
     * Execution order within the same phase.
     * Lower numbers run first. Default is 50.
     */
    int order() default 50;

    /**
     * Whether this skill targets individual files or the whole project.
     */
    SkillScope scope() default SkillScope.FILE;

    /**
     * Skills that must have executed before this one.
     * The planner enforces ordering and detects cycles.
     */
    Class<? extends MigrationSkill>[] after() default {};

    /**
     * Optional factory for creating parameterized instances.
     * Defaults to {@link SkillFactory.None} (no factory — class must have a no-arg constructor).
     */
    Class<? extends SkillFactory<?>> factory() default SkillFactory.None.class;
}
