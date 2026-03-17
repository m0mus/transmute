package io.transmute.catalog;

import io.transmute.migration.Migration;

import java.util.List;

/**
 * Discovers {@link Migration} implementations on the classpath.
 *
 * <p>Loads all markdown recipes and features from classpath {@code recipes/} and
 * {@code features/} directories via {@link MarkdownMigrationLoader}.
 */
public class MigrationDiscovery {

    public record DiscoveryResult(List<Migration> migrations) {}

    public DiscoveryResult discover() {
        var migrations = new MarkdownMigrationLoader().load();
        return new DiscoveryResult(List.copyOf(migrations));
    }
}
