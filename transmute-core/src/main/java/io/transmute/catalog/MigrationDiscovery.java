package io.transmute.catalog;

import io.github.classgraph.ClassGraph;
import io.transmute.migration.Migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers {@link Migration} implementations on the classpath.
 *
 * <p>Always loads markdown recipes and features from classpath {@code recipes/} and
 * {@code features/} directories. Additionally scans the caller-supplied packages for
 * concrete classes implementing {@link Migration}.
 */
public class MigrationDiscovery {

    public record DiscoveryResult(List<Migration> migrations) {}

    /**
     * Loads all migrations: markdown recipes/features from the classpath, plus any
     * concrete {@link Migration} implementations found in {@code migrationPackages}.
     *
     * @param migrationPackages packages to scan for Java migrations (may be empty)
     */
    public DiscoveryResult discover(List<String> migrationPackages) {
        var migrations = new ArrayList<Migration>();

        // Always load markdown recipes and features from classpath
        var aiMigrations = new MarkdownMigrationLoader().load();
        migrations.addAll(aiMigrations);
        if (!aiMigrations.isEmpty()) {
            System.out.println("[MigrationDiscovery] Loaded " + aiMigrations.size() + " recipe(s)/feature(s).");
        }

        // Scan specified packages for Java Migration implementations
        if (migrationPackages != null && !migrationPackages.isEmpty()) {
            try (var result = new ClassGraph()
                    .acceptPackages(migrationPackages.toArray(String[]::new))
                    .enableClassInfo()
                    .scan()) {

                for (var classInfo : result.getClassesImplementing(Migration.class)) {
                    if (classInfo.isAbstract() || classInfo.isInterface()) {
                        continue;
                    }
                    try {
                        var cls = classInfo.loadClass(Migration.class);
                        migrations.add(cls.getDeclaredConstructor().newInstance());
                    } catch (Exception e) {
                        System.err.println("[MigrationDiscovery] Failed to instantiate "
                                + classInfo.getName() + ": " + e.getMessage());
                    }
                }
            }
        }

        return new DiscoveryResult(migrations);
    }
}
