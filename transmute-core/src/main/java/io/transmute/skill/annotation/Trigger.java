package io.transmute.skill.annotation;

import java.lang.annotation.*;

/**
 * Declares one set of AND-conditions that trigger a skill.
 *
 * <p>Multiple {@code @Trigger} annotations on the same skill are combined with OR
 * semantics: the skill fires when <em>any</em> trigger's conditions are all met.
 *
 * <p>Within a single {@code @Trigger}, all non-empty arrays are ANDed together.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(Triggers.class)
public @interface Trigger {

    /** Fully-qualified import prefixes that must appear in the file. */
    String[] imports() default {};

    /** Fully-qualified annotation type names that must appear on the file's class. */
    String[] annotations() default {};

    /** FQN of super-types (classes or interfaces) the file's class must extend/implement. */
    String[] superTypes() default {};

    /** Regex patterns matched against active compile error messages. */
    String[] compileErrors() default {};

    /** Inventory signal strings that must be present in the project inventory. */
    String[] signals() default {};
}
