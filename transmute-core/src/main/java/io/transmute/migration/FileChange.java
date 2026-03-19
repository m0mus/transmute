package io.transmute.migration;

/**
 * Records the before/after state of a single file modified by a migration.
 */
public record FileChange(String file, String before, String after, FileOutcome outcome) {

    /**
     * Returns {@code true} when the content actually changed.
     */
    public boolean isChanged() {
        return !before.equals(after);
    }

    /**
     * Constructs a {@code FileChange} and infers the outcome from content:
     * {@code UNCHANGED} if before equals after, {@code COMMENTED} if the after
     * content contains a {@code TRANSMUTE[} marker, {@code CONVERTED} otherwise.
     */
    public static FileChange of(String file, String before, String after) {
        FileOutcome outcome;
        if (before.equals(after)) {
            outcome = FileOutcome.UNCHANGED;
        } else if (after.contains("TRANSMUTE[")) {
            outcome = FileOutcome.COMMENTED;
        } else {
            outcome = FileOutcome.CONVERTED;
        }
        return new FileChange(file, before, after, outcome);
    }
}
