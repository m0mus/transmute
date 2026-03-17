package io.transmute.catalog;

/**
 * Estimated confidence that a migration will successfully migrate its target files.
 */
public enum MigrationConfidence {
    /** Deterministic transformation with no AI involved. */
    HIGH,
    /** Transformation heuristics are solid but some edge cases may require review. */
    MEDIUM,
    /** AI-driven or highly context-dependent; manual review recommended. */
    LOW
}
