package io.transmute.migration;

/**
 * Records the before/after state of a single file modified by a migration.
 */
public record FileChange(String file, String before, String after) {

    /**
     * Returns {@code true} when the content actually changed.
     */
    public boolean isChanged() {
        return !before.equals(after);
    }
}
