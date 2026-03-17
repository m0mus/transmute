package io.transmute.catalog;

import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;
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
 *   <li>Resolve {@code targetFiles} for FILE-scope migrations</li>
 *   <li>Sort by {@code order} then migration name</li>
 *   <li>Return the plan</li>
 * </ol>
 */
public class MigrationPlanner {

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

        // 2. Deterministic order sort
        var sorted = sortByOrder(triggered);

        // 3. Build plan entries
        var entries = new ArrayList<MigrationPlan.MigrationExecutionEntry>();
        for (var migration : sorted) {
            var scope = ((AiMigrationMetadata) migration).skillScope();
            var targetFiles = scope == MigrationScope.FILE
                    ? resolveTargetFiles(migration, inventory, compileErrors)
                    : List.<String>of();

            if (scope == MigrationScope.FILE && targetFiles.isEmpty()) {
                continue;
            }

            entries.add(new MigrationPlan.MigrationExecutionEntry(migration, targetFiles, MigrationConfidence.LOW));
        }

        return new MigrationPlan(entries);
    }

    // ── Trigger evaluation ────────────────────────────────────────────────────

    private boolean isTriggered(Migration migration, ProjectInventory inventory, List<String> compileErrors) {
        var sm = (AiMigrationMetadata) migration;
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

    private boolean markdownTriggerFires(
            MarkdownTrigger trigger,
            ProjectInventory inventory,
            List<String> compileErrors) {

        // signals[]: all must be present
        for (var signal : trigger.signals()) {
            if (!inventory.getSignals().contains(signal)) {
                return false;
            }
        }
        // files[]: at least one must exist in project root
        if (!trigger.files().isEmpty()) {
            var root = inventory.getRootDir() != null
                    ? Path.of(inventory.getRootDir()).toAbsolutePath().normalize()
                    : Path.of(".").toAbsolutePath().normalize();
            boolean anyPresent = trigger.files().stream().anyMatch(f -> root.resolve(f).toFile().exists());
            if (!anyPresent) {
                return false;
            }
        }
        // compileErrors[]: all patterns must match at least one error
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

    // ── Target file resolution ────────────────────────────────────────────────

    private List<String> resolveTargetFiles(Migration migration, ProjectInventory inventory, List<String> compileErrors) {
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
            if (!triggerPreconditionsSatisfied(trigger, inventory, compileErrors)) {
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

    private boolean hasJavaFileConditions(MarkdownTrigger trigger) {
        return !trigger.imports().isEmpty()
                || !trigger.annotations().isEmpty()
                || !trigger.superTypes().isEmpty();
    }

    private boolean triggerPreconditionsSatisfied(
            MarkdownTrigger trigger,
            ProjectInventory inventory,
            List<String> compileErrors) {
        for (var signal : trigger.signals()) {
            if (!inventory.getSignals().contains(signal)) {
                return false;
            }
        }
        if (!trigger.files().isEmpty()) {
            var root = inventory.getRootDir() != null
                    ? Path.of(inventory.getRootDir()).toAbsolutePath().normalize()
                    : Path.of(".").toAbsolutePath().normalize();
            boolean anyPresent = trigger.files().stream().anyMatch(f -> root.resolve(f).toFile().exists());
            if (!anyPresent) {
                return false;
            }
        }
        for (var regex : trigger.compileErrors()) {
            var pattern = Pattern.compile(regex);
            if (compileErrors.stream().noneMatch(e -> pattern.matcher(e).find())) {
                return false;
            }
        }
        return true;
    }
}
