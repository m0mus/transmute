package io.transmute.catalog;

/**
 * Records the highest state reached for a file/skill combination.
 */
public enum LogStatus {
    /** The skill identified the file as a candidate. */
    TARGETED,
    /** The skill attempted to transform the file. */
    ATTEMPTED,
    /** The skill produced a net change to the file. */
    CHANGED
}
