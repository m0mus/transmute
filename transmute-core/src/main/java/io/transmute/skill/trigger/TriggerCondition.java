package io.transmute.skill.trigger;

import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;

import java.util.List;

/**
 * A single predicate that determines whether a skill should run against a file.
 */
@FunctionalInterface
public interface TriggerCondition {

    /**
     * Evaluates the condition.
     *
     * @param inventory    the full project inventory
     * @param file         the candidate file (may be {@code null} for PROJECT-scope skills)
     * @param compileErrors active compile error messages
     * @return {@code true} when the condition is met
     */
    boolean test(ProjectInventory inventory, JavaFileInfo file, List<String> compileErrors);
}
