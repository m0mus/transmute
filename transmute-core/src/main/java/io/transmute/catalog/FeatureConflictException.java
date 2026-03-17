package io.transmute.catalog;

/**
 * Thrown at startup when two features declare overlapping FQN ownership
 * ({@code transforms.annotations} or {@code transforms.types}).
 *
 * <p>Features must have non-overlapping FQN scopes so the combined prompt can
 * unambiguously assign "DO NOT touch" sections without contradictions.
 */
public class FeatureConflictException extends RuntimeException {

    public FeatureConflictException(String message) {
        super(message);
    }
}
