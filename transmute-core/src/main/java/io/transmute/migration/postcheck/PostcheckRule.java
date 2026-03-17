package io.transmute.migration.postcheck;

import io.transmute.migration.FileChange;

/**
 * A single postcondition check applied to a file change produced by a migration.
 */
@FunctionalInterface
public interface PostcheckRule {

    /**
     * Evaluates this rule against the given file change.
     *
     * @param change the before/after file state produced by a migration
     * @return the check result
     */
    PostcheckResult check(FileChange change);
}
