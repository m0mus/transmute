package io.transmute.catalog;

import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigration;
import io.transmute.migration.AiMigrationMetadata;
import io.transmute.migration.MarkdownTrigger;
import io.transmute.migration.Migration;
import io.transmute.migration.MigrationScope;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Builds a {@link MigrationPlan} from discovered migrations and a project inventory.
 *
 * <p>Steps:
 * <ol>
 *   <li>Evaluate triggers for each migration against the inventory</li>
 *   <li>Resolve {@code targetFiles} for FILE-scope AI migrations</li>
 *   <li>Topological sort by ordering/dependencies</li>
 *   <li>Return the plan</li>
 * </ol>
 *
 * <p>Java migrations implement {@link Migration} directly; scope and triggers are
 * expressed via interface methods. AI migrations use {@link AiMigrationMetadata}.
 */
public class MigrationPlanner {

    private final boolean allowOrderConflicts;

    public MigrationPlanner() { this(false); }

    public MigrationPlanner(boolean allowOrderConflicts) {
        this.allowOrderConflicts = allowOrderConflicts;
    }

    public MigrationPlan plan(
            List<Migration> migrations,
            ProjectInventory inventory,
            List<String> compileErrors) {

        // 1. Filter to triggered migrations
        var triggered = new ArrayList<Migration>();
        for (var migration : migrations) {
            if (isTriggered(migration, inventory, compileErrors)) {
                triggered.add(migration);
            }
        }

        // 2. Topological sort
        var sorted = topoSort(triggered);

        // 3. Build plan entries
        var entries = new ArrayList<MigrationPlan.MigrationExecutionEntry>();
        for (var migration : sorted) {
            var scope = getScope(migration);
            var targetFiles = scope == MigrationScope.FILE
                    ? resolveTargetFiles(migration, inventory)
                    : List.<String>of();

            if (scope == MigrationScope.FILE && targetFiles.isEmpty()) {
                continue;
            }

            var aiInvolved = migration instanceof AiMigration;
            var confidence = aiInvolved ? MigrationConfidence.LOW : MigrationConfidence.HIGH;
            entries.add(new MigrationPlan.MigrationExecutionEntry(migration, targetFiles, confidence, aiInvolved));
        }

        return new MigrationPlan(entries);
    }

    // ── Trigger evaluation ────────────────────────────────────────────────────

    private boolean isTriggered(Migration migration, ProjectInventory inventory, List<String> compileErrors) {
        if (migration instanceof AiMigrationMetadata sm) {
            var triggers = sm.skillTriggers();
            if (triggers.isEmpty()) {
                return true;
            }
            for (var trigger : triggers) {
                if (markdownTriggerFires(trigger, inventory, compileErrors)) {
                    return true;
                }
            }
            return false;
        }
        return migration.isTriggered(inventory);
    }

    private boolean markdownTriggerFires(
            MarkdownTrigger trigger,
            ProjectInventory inventory,
            List<String> compileErrors) {

        // signals[]: AND — all must be present
        for (var signal : trigger.signals()) {
            if (!inventory.getSignals().contains(signal)) {
                return false;
            }
        }
        // files[]: AND — at least one must exist in project root
        if (!trigger.files().isEmpty()) {
            var root = inventory.getRootDir() != null ? Path.of(inventory.getRootDir()) : Path.of(".");
            boolean anyPresent = trigger.files().stream().anyMatch(f -> root.resolve(f).toFile().exists());
            if (!anyPresent) {
                return false;
            }
        }
        // compileErrors[]: AND — all patterns must match at least one error
        for (var regex : trigger.compileErrors()) {
            var pattern = Pattern.compile(regex);
            if (compileErrors.stream().noneMatch(e -> pattern.matcher(e).find())) {
                return false;
            }
        }
        // File-level conditions — at least one Java file must match
        if (trigger.imports().isEmpty() && trigger.annotations().isEmpty() && trigger.superTypes().isEmpty()) {
            return true;
        }
        return inventory.getJavaFiles().stream().anyMatch(file -> fileMatchesTrigger(file, trigger));
    }

    private boolean fileMatchesTrigger(JavaFileInfo file, MarkdownTrigger trigger) {
        if (!trigger.imports().isEmpty()) {
            boolean anyMatch = trigger.imports().stream()
                    .anyMatch(imp -> file.imports().stream().anyMatch(i -> i.startsWith(imp)));
            if (!anyMatch) return false;
        }
        if (!trigger.annotations().isEmpty()) {
            boolean anyMatch = trigger.annotations().stream()
                    .anyMatch(ann -> file.annotationTypes().contains(ann));
            if (!anyMatch) return false;
        }
        if (!trigger.superTypes().isEmpty()) {
            boolean anyMatch = trigger.superTypes().stream()
                    .anyMatch(st -> file.superTypes().contains(st));
            if (!anyMatch) return false;
        }
        return true;
    }

    // ── Scope derivation ──────────────────────────────────────────────────────

    /**
     * For AI migrations: FILE if any trigger uses Java file conditions; PROJECT otherwise.
     * For Java migrations: PROJECT always (they handle their own file iteration).
     */
    private MigrationScope getScope(Migration migration) {
        if (migration instanceof AiMigrationMetadata sm) {
            for (var trigger : sm.skillTriggers()) {
                if (!trigger.imports().isEmpty()
                        || !trigger.annotations().isEmpty()
                        || !trigger.superTypes().isEmpty()) {
                    return MigrationScope.FILE;
                }
            }
            return MigrationScope.PROJECT;
        }
        return MigrationScope.PROJECT;
    }

    // ── Target file resolution (FILE-scope AI migrations only) ────────────────

    private List<String> resolveTargetFiles(Migration migration, ProjectInventory inventory) {
        if (migration instanceof AiMigrationMetadata sm) {
            var triggers = sm.skillTriggers();
            if (triggers.isEmpty()) {
                return inventory.getJavaFiles().stream().map(JavaFileInfo::sourceFile).toList();
            }
            return inventory.getJavaFiles().stream()
                    .filter(file -> triggers.stream().anyMatch(t -> fileMatchesTrigger(file, t)))
                    .map(JavaFileInfo::sourceFile)
                    .toList();
        }
        return List.of();
    }

    // ── Topological sort ──────────────────────────────────────────────────────

    private List<Migration> topoSort(List<Migration> migrations) {
        var byName = new LinkedHashMap<String, Migration>();
        for (var m : migrations) {
            byName.put(m.name(), m);
        }

        var inDegree = new IdentityHashMap<Migration, Integer>();
        var edges = new IdentityHashMap<Migration, List<Migration>>();
        for (var m : migrations) {
            inDegree.putIfAbsent(m, 0);
        }

        for (var m : migrations) {
            for (var dep : resolveDeps(m, byName)) {
                edges.computeIfAbsent(dep, k -> new ArrayList<>()).add(m);
                inDegree.merge(m, 1, Integer::sum);
            }
        }

        var queue = new PriorityQueue<Migration>(Comparator.comparingInt(this::getOrder));
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        var result = new ArrayList<Migration>();
        while (!queue.isEmpty()) {
            var node = queue.poll();
            result.add(node);
            for (var next : edges.getOrDefault(node, List.of())) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }

        if (result.size() != migrations.size()) {
            throw new IllegalStateException("Cycle detected in migration dependency graph.");
        }
        return result;
    }

    private List<Migration> resolveDeps(Migration migration, Map<String, Migration> byName) {
        var afterNames = migration instanceof AiMigrationMetadata sm
                ? sm.skillAfterNames()
                : migration.after();

        var deps = new ArrayList<Migration>();
        for (var name : afterNames) {
            var dep = byName.get(name);
            if (dep != null) {
                deps.add(dep);
            } else if (!allowOrderConflicts) {
                System.err.println("[MigrationPlanner] Warning: after='" + name
                        + "' referenced by '" + migration.name()
                        + "' is not in the active migration set.");
            }
        }
        return deps;
    }

    private int getOrder(Migration migration) {
        if (migration instanceof AiMigrationMetadata sm) return sm.skillOrder();
        return migration.order();
    }
}
