package io.transmute.catalog;

import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigrationMetadata;
import io.transmute.migration.MarkdownTrigger;
import io.transmute.migration.Migration;
import io.transmute.migration.MigrationScope;

import java.nio.file.Path;
import java.util.*;

/**
 * Builds a {@link MigrationPlan} from discovered migrations and a project inventory.
 *
 * <p>Steps:
 * <ol>
 *   <li>Evaluate triggers for each migration against the inventory</li>
 *   <li>Resolve {@code targetFiles} for FILE-scope migrations</li>
 *   <li>Sort by {@code order} then migration name</li>
 *   <li>Return the plan</li>
 * </ol>
 */
public class MigrationPlanner {

    public MigrationPlan plan(
            List<Migration> migrations,
            ProjectInventory inventory) {

        // 1. Filter to triggered migrations
        var triggered = new ArrayList<Migration>();
        for (var migration : migrations) {
            if (isMigrationTriggered(migration, inventory)) {
                triggered.add(migration);
            }
        }

        // 2. Deterministic order sort
        var sorted = sortByOrder(triggered);

        // 3. Build plan entries
        var entries = new ArrayList<MigrationPlan.MigrationExecutionEntry>();
        for (var migration : sorted) {
            var scope = ((AiMigrationMetadata) migration).skillScope();
            var targetFiles = scope == MigrationScope.FILE
                    ? resolveTargetFiles(migration, inventory)
                    : List.<String>of();

            if (scope == MigrationScope.FILE && targetFiles.isEmpty()) {
                continue;
            }

            entries.add(new MigrationPlan.MigrationExecutionEntry(migration, targetFiles));
        }

        return new MigrationPlan(entries);
    }

    // ── Trigger evaluation ────────────────────────────────────────────────────

    private boolean isMigrationTriggered(Migration migration, ProjectInventory inventory) {
        var sm = (AiMigrationMetadata) migration;
        var triggers = sm.skillTriggers();
        if (triggers.isEmpty()) {
            return true;
        }
        for (var trigger : triggers) {
            if (!triggerPreconditionsSatisfied(trigger, inventory)) {
                continue;
            }
            if (!hasJavaFileConditions(trigger)) {
                return true;
            }
            if (inventory.getJavaFiles().stream().anyMatch(file -> fileMatchesTrigger(file, trigger))) {
                return true;
            }
        }
        return false;
    }

    private boolean fileMatchesTrigger(JavaFileInfo file, MarkdownTrigger trigger) {
        if (!trigger.excludeImports().isEmpty()) {
            boolean excluded = trigger.excludeImports().stream()
                    .anyMatch(exc -> file.imports().stream().anyMatch(i -> i.startsWith(exc)));
            if (excluded) return false;
        }
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

    // ── Target file resolution ────────────────────────────────────────────────

    private List<String> resolveTargetFiles(Migration migration, ProjectInventory inventory) {
        var sm = (AiMigrationMetadata) migration;
        var triggers = sm.skillTriggers();
        if (triggers.isEmpty()) {
            return inventory.getJavaFiles().stream().map(JavaFileInfo::sourceFile).toList();
        }
        var targets = new LinkedHashSet<String>();
        var root = inventory.getRootDir() != null
                ? Path.of(inventory.getRootDir()).toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();

        for (var trigger : triggers) {
            if (!triggerPreconditionsSatisfied(trigger, inventory)) {
                continue;
            }
            if (hasJavaFileConditions(trigger)) {
                inventory.getJavaFiles().stream()
                        .filter(file -> fileMatchesTrigger(file, trigger))
                        .map(JavaFileInfo::sourceFile)
                        .forEach(targets::add);
            }
            for (var path : trigger.files()) {
                var candidate = root.resolve(path).normalize();
                if (candidate.toFile().exists() && candidate.startsWith(root)) {
                    targets.add(root.relativize(candidate).toString());
                }
            }
        }

        return List.copyOf(targets);
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    private List<Migration> sortByOrder(List<Migration> migrations) {
        return migrations.stream()
                .sorted(Comparator.comparingInt(this::getOrder).thenComparing(Migration::name))
                .toList();
    }

    private int getOrder(Migration migration) {
        return ((AiMigrationMetadata) migration).skillOrder();
    }

    // ── Untouched-file diagnostics ────────────────────────────────────────────

    /**
     * For each untouched source file, explains why each migration did not target it.
     * Intended for authoring diagnostics — written to {@code migration-untouched.txt}.
     *
     * @param untouchedFiles source file paths not targeted by any migration
     * @param allMigrations  all discovered migrations (including those not in the plan)
     * @param inventory      project inventory for file lookup
     * @return map from file path to per-migration miss reasons, in discovery order
     */
    public Map<String, List<String>> diagnoseUntouched(
            List<String> untouchedFiles,
            List<Migration> allMigrations,
            ProjectInventory inventory) {

        var result = new LinkedHashMap<String, List<String>>();
        for (var file : untouchedFiles) {
            var fileInfo = inventory.fileByPath(file);
            if (fileInfo == null) continue;
            var reasons = new ArrayList<String>();
            for (var migration : allMigrations) {
                var sm = (AiMigrationMetadata) migration;
                var triggers = sm.skillTriggers();
                if (triggers.stream().noneMatch(this::hasJavaFileConditions)) continue;
                var miss = bestMissReason(fileInfo, triggers);
                if (miss != null) {
                    reasons.add(sm.skillName() + ": " + miss);
                }
            }
            if (!reasons.isEmpty()) {
                result.put(file, List.copyOf(reasons));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Finds the trigger group with the most matching conditions and returns a
     * human-readable description of what was missing. Returns {@code null} if
     * every trigger group fully matched (the file should have been targeted).
     */
    private String bestMissReason(JavaFileInfo file, List<MarkdownTrigger> triggers) {
        String best = null;
        int bestScore = -1;
        for (var trigger : triggers) {
            if (!hasJavaFileConditions(trigger)) continue;
            var missed = new ArrayList<String>();
            int score = 0;
            if (!trigger.imports().isEmpty()) {
                boolean hit = trigger.imports().stream()
                        .anyMatch(imp -> file.imports().stream().anyMatch(i -> i.startsWith(imp)));
                if (hit) score++;
                else missed.add("imports [" + summarizeTrigger(trigger.imports()) + "] not found");
            }
            if (!trigger.annotations().isEmpty()) {
                boolean hit = trigger.annotations().stream()
                        .anyMatch(ann -> file.annotationTypes().contains(ann));
                if (hit) score++;
                else missed.add("annotation [" + summarizeTrigger(trigger.annotations()) + "] not used");
            }
            if (!trigger.superTypes().isEmpty()) {
                boolean hit = trigger.superTypes().stream()
                        .anyMatch(st -> file.superTypes().contains(st));
                if (hit) score++;
                else missed.add("superType [" + summarizeTrigger(trigger.superTypes()) + "] not extended");
            }
            if (missed.isEmpty()) continue; // this trigger group fully matched
            if (score > bestScore) {
                bestScore = score;
                var partial = score > 0
                        ? "  (" + score + " condition" + (score > 1 ? "s" : "") + " matched)" : "";
                best = String.join("; ", missed) + partial;
            }
        }
        return best;
    }

    private static String summarizeTrigger(List<String> items) {
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + ", " + items.get(1);
        return items.get(0) + " +" + (items.size() - 1) + " more";
    }

    private boolean hasJavaFileConditions(MarkdownTrigger trigger) {
        return !trigger.imports().isEmpty()
                || !trigger.annotations().isEmpty()
                || !trigger.superTypes().isEmpty();
    }

    private boolean triggerPreconditionsSatisfied(MarkdownTrigger trigger, ProjectInventory inventory) {
        if (!trigger.files().isEmpty()) {
            var root = inventory.getRootDir() != null
                    ? Path.of(inventory.getRootDir()).toAbsolutePath().normalize()
                    : Path.of(".").toAbsolutePath().normalize();
            boolean anyPresent = trigger.files().stream().anyMatch(f -> root.resolve(f).toFile().exists());
            if (!anyPresent) {
                return false;
            }
        }
        return true;
    }
}
