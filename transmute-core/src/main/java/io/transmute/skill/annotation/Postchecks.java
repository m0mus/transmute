package io.transmute.skill.annotation;

import java.lang.annotation.*;

/**
 * Declares postconditions that the {@link io.transmute.skill.postcheck.PostcheckRunner}
 * verifies against every {@link io.transmute.skill.FileChange} produced by a skill.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Postchecks {

    /** Import prefixes that must NOT appear in the output file. */
    String[] forbidImports() default {};

    /** Regex patterns that must NOT match anywhere in the output file. */
    String[] forbidPatterns() default {};

    /** Substrings that must appear in at least one TODO in {@link io.transmute.skill.SkillResult#todos()}. */
    String[] requireTodos() default {};
}
