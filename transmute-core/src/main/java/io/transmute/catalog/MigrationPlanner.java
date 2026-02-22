package io.transmute.catalog;

import io.transmute.inventory.ProjectInventory;
import io.transmute.skill.MigrationSkill;
import io.transmute.skill.annotation.Skill;
import io.transmute.skill.annotation.SkillScope;
import io.transmute.skill.annotation.Trigger;
import io.transmute.skill.annotation.Triggers;
import io.transmute.skill.trigger.TriggerPredicates;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Builds a {@link MigrationPlan} from discovered skills and a project inventory.
 *
 * <p>Steps:
 * <ol>
 *   <li>Evaluate triggers for each skill against the inventory + compile errors</li>
 *   <li>Resolve {@code targetFiles} for FILE-scope skills</li>
 *   <li>Validate {@code @Skill(after=...)} ordering; detect cycles</li>
 *   <li>Sort skills by order/dependencies</li>
 *   <li>Return the plan</li>
 * </ol>
 */
public class MigrationPlanner {

    private final boolean allowOrderConflicts;

    public MigrationPlanner() {
        this(false);
    }

    public MigrationPlanner(boolean allowOrderConflicts) {
        this.allowOrderConflicts = allowOrderConflicts;
    }

    /**
     * Builds a migration plan.
     *
     * @param skills        discovered skills
     * @param inventory     scanned project inventory
     * @param compileErrors active compile error messages (may be empty)
     * @return the ordered plan
     */
    public MigrationPlan plan(
            List<MigrationSkill> skills,
            ProjectInventory inventory,
            List<String> compileErrors) {

        // 1. Filter skills that have at least one satisfied trigger
        var triggered = new ArrayList<MigrationSkill>();
        for (var skill : skills) {
            if (isTriggered(skill, inventory, compileErrors)) {
                triggered.add(skill);
            }
        }

        // 2. Topological sort by @Skill(after=...)
        var sorted = topoSort(triggered);

        // 3. Build plan entries
        var entries = new ArrayList<MigrationPlan.SkillExecutionEntry>();
        for (var skill : sorted) {
            var ann = skill.getClass().getAnnotation(Skill.class);
            var scope = ann != null ? ann.scope() : SkillScope.FILE;
            var targetFiles = scope == SkillScope.FILE
                    ? resolveTargetFiles(skill, inventory, compileErrors)
                    : List.<String>of();

            if (scope == SkillScope.FILE && targetFiles.isEmpty()) {
                // No files matched — skip
                continue;
            }

            var confidence = computeConfidence(skill, ann);
            var aiInvolved = isAiInvolved(skill);
            entries.add(new MigrationPlan.SkillExecutionEntry(skill, targetFiles, confidence, aiInvolved));
        }

        return new MigrationPlan(entries);
    }

    // ── Trigger evaluation ────────────────────────────────────────────────────

    private boolean isTriggered(MigrationSkill skill, ProjectInventory inventory, List<String> compileErrors) {
        var triggers = collectTriggers(skill.getClass());
        if (triggers.isEmpty()) {
            // No triggers declared — always run
            return true;
        }
        // OR across triggers
        for (var trigger : triggers) {
            if (triggerMatchesAnyFile(trigger, inventory, compileErrors)) {
                return true;
            }
        }
        return false;
    }

    private boolean triggerMatchesAnyFile(Trigger trigger, ProjectInventory inventory, List<String> compileErrors) {
        // For signals and compileErrors check without a file
        if (trigger.signals().length > 0) {
            for (var signal : trigger.signals()) {
                if (!inventory.getSignals().contains(signal)) {
                    return false;
                }
            }
        }
        if (trigger.compileErrors().length > 0) {
            for (var regex : trigger.compileErrors()) {
                var pattern = Pattern.compile(regex);
                boolean found = compileErrors.stream().anyMatch(e -> pattern.matcher(e).find());
                if (!found) {
                    return false;
                }
            }
        }
        // File-level conditions
        if (trigger.imports().length == 0 && trigger.annotations().length == 0 && trigger.superTypes().length == 0) {
            // Only non-file conditions (signals/errors) — already evaluated
            return true;
        }
        return inventory.getJavaFiles().stream().anyMatch(file -> {
            for (var imp : trigger.imports()) {
                if (file.imports().stream().noneMatch(i -> i.startsWith(imp))) {
                    return false;
                }
            }
            for (var ann : trigger.annotations()) {
                if (!file.annotationTypes().contains(ann)) {
                    return false;
                }
            }
            for (var superType : trigger.superTypes()) {
                if (!file.superTypes().contains(superType)) {
                    return false;
                }
            }
            return true;
        });
    }

    // ── Target file resolution ────────────────────────────────────────────────

    private List<String> resolveTargetFiles(
            MigrationSkill skill,
            ProjectInventory inventory,
            List<String> compileErrors) {

        var triggers = collectTriggers(skill.getClass());
        if (triggers.isEmpty()) {
            // No file-level triggers — include all files
            return inventory.getJavaFiles().stream().map(f -> f.sourceFile()).toList();
        }

        return inventory.getJavaFiles().stream()
                .filter(file -> triggers.stream().anyMatch(trigger -> {
                    for (var imp : trigger.imports()) {
                        if (file.imports().stream().noneMatch(i -> i.startsWith(imp))) {
                            return false;
                        }
                    }
                    for (var ann : trigger.annotations()) {
                        if (!file.annotationTypes().contains(ann)) {
                            return false;
                        }
                    }
                    for (var superType : trigger.superTypes()) {
                        if (!file.superTypes().contains(superType)) {
                            return false;
                        }
                    }
                    return true;
                }))
                .map(f -> f.sourceFile())
                .toList();
    }

    // ── Topological sort ──────────────────────────────────────────────────────

    private List<MigrationSkill> topoSort(List<MigrationSkill> skills) {
        // Build adjacency: skillClass -> depends on these classes
        var byClass = new LinkedHashMap<Class<?>, MigrationSkill>();
        for (var s : skills) {
            byClass.put(s.getClass(), s);
        }

        // Kahn's algorithm
        var inDegree = new LinkedHashMap<Class<?>, Integer>();
        var edges = new LinkedHashMap<Class<?>, List<Class<?>>>();  // before -> after

        for (var s : skills) {
            inDegree.putIfAbsent(s.getClass(), 0);
            var ann = s.getClass().getAnnotation(Skill.class);
            if (ann != null) {
                for (var dep : ann.after()) {
                    if (byClass.containsKey(dep)) {
                        edges.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.getClass());
                        inDegree.merge(s.getClass(), 1, Integer::sum);
                    } else if (!allowOrderConflicts) {
                        // Dependency declared but not in plan — warn but continue
                        System.err.println("[MigrationPlanner] Warning: @Skill(after=" + dep.getSimpleName()
                                + ") referenced by " + s.getClass().getSimpleName()
                                + " is not in the active skill set.");
                    }
                }
            }
        }

        // Sort by order within same topo-level
        var queue = new PriorityQueue<MigrationSkill>(
                Comparator.comparingInt(s -> {
                    var a = s.getClass().getAnnotation(Skill.class);
                    return a != null ? a.order() : 50;
                })
        );
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(byClass.get(entry.getKey()));
            }
        }

        var result = new ArrayList<MigrationSkill>();
        while (!queue.isEmpty()) {
            var node = queue.poll();
            result.add(node);
            for (var next : edges.getOrDefault(node.getClass(), List.of())) {
                var deg = inDegree.merge(next, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(byClass.get(next));
                }
            }
        }

        if (result.size() != skills.size()) {
            throw new IllegalStateException("Cycle detected in @Skill(after=...) dependency graph.");
        }

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Trigger> collectTriggers(Class<?> skillClass) {
        var triggers = new ArrayList<Trigger>();
        var single = skillClass.getAnnotation(Trigger.class);
        if (single != null) {
            triggers.add(single);
        }
        var container = skillClass.getAnnotation(Triggers.class);
        if (container != null) {
            triggers.addAll(Arrays.asList(container.value()));
        }
        return triggers;
    }

    private SkillConfidence computeConfidence(MigrationSkill skill, Skill ann) {
        if (isAiInvolved(skill)) {
            return SkillConfidence.LOW;
        }
        return SkillConfidence.HIGH;
    }

    private boolean isAiInvolved(MigrationSkill skill) {
        // Heuristic: check if the skill class name contains "Ai" or "LLM"
        var name = skill.getClass().getSimpleName();
        return name.contains("Ai") || name.contains("AI") || name.contains("LLM")
                || name.contains("Llm") || name.contains("Gpt");
    }
}
