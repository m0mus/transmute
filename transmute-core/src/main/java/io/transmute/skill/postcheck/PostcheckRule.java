package io.transmute.skill.postcheck;

import io.transmute.skill.FileChange;

/**
 * A single postcondition check applied to a file change produced by a skill.
 */
@FunctionalInterface
public interface PostcheckRule {

    /**
     * Evaluates this rule against the given file change.
     *
     * @param change the before/after file state produced by a skill
     * @return the check result
     */
    PostcheckResult check(FileChange change);
}
