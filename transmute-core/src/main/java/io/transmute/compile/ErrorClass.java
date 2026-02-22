package io.transmute.compile;

/**
 * Classification of a compile error by its likely cause and resolution path.
 */
public enum ErrorClass {
    /** A known migration skill should be able to handle this error. */
    SKILL_GAP,
    /** A compile-only issue (missing import, wrong type) unrelated to migration gaps. */
    COMPILE_ONLY,
    /** An unknown error that may require manual attention or a new skill. */
    NOVEL
}
