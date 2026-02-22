package io.transmute.skill.annotation;

import io.transmute.skill.MigrationSkill;

import java.lang.annotation.*;

/**
 * References a fallback skill class to invoke when this skill fails
 * its {@link Postchecks} verification.
 *
 * <p>The fallback skill is instantiated by the planner using the same
 * discovery mechanism as primary skills.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Fallback {

    Class<? extends MigrationSkill> value();
}
