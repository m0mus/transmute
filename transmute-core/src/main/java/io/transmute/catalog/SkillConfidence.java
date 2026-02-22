package io.transmute.catalog;

/**
 * Estimated confidence that a skill will successfully migrate its target files.
 */
public enum SkillConfidence {
    /** Deterministic transformation with no AI involved. */
    HIGH,
    /** Transformation heuristics are solid but some edge cases may require review. */
    MEDIUM,
    /** AI-driven or highly context-dependent; manual review recommended. */
    LOW
}
