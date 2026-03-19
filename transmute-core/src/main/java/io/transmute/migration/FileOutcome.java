package io.transmute.migration;

/** Per-file migration outcome recorded during {@code applyToFile}. */
public enum FileOutcome { CONVERTED, PARTIAL, COMMENTED, UNCHANGED, FAILED }
