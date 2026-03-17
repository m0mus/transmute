package io.transmute.catalog;

import io.transmute.migration.Migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only log of all skill actions taken during a migration run.
 * Thread-safe.
 */
public class MigrationLog {

    private final List<MigrationLogEntry> entries = new CopyOnWriteArrayList<>();

    public void recordTargeted(Class<? extends Migration> migration, String file) {
        entries.add(new MigrationLogEntry(migration, file, LogStatus.TARGETED));
    }

    public void recordAttempted(Class<? extends Migration> migration, String file) {
        entries.add(new MigrationLogEntry(migration, file, LogStatus.ATTEMPTED));
    }

    public void recordChanged(Class<? extends Migration> migration, String file) {
        entries.add(new MigrationLogEntry(migration, file, LogStatus.CHANGED));
    }

    /**
     * Returns all log entries for the given output file path, in insertion order.
     */
    public List<MigrationLogEntry> history(String file) {
        return entries.stream()
                .filter(e -> e.file().equals(file))
                .toList();
    }

    /**
     * Returns all log entries in insertion order.
     */
    public List<MigrationLogEntry> allEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
