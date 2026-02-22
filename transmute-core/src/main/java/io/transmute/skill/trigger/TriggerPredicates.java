package io.transmute.skill.trigger;

import java.util.regex.Pattern;

/**
 * Factory methods for common {@link TriggerCondition} predicates.
 */
public final class TriggerPredicates {

    private TriggerPredicates() {}

    /** Matches when the file contains an import whose FQN starts with {@code prefix}. */
    public static TriggerCondition hasImport(String prefix) {
        return (inventory, file, errors) ->
                file != null && file.imports().stream().anyMatch(i -> i.startsWith(prefix));
    }

    /** Matches when the file's class is annotated with the given FQN annotation type. */
    public static TriggerCondition hasAnnotation(String fqnAnnotation) {
        return (inventory, file, errors) ->
                file != null && file.annotationTypes().contains(fqnAnnotation);
    }

    /** Matches when the file's class extends or implements the given FQN type. */
    public static TriggerCondition hasSuperType(String fqnType) {
        return (inventory, file, errors) ->
                file != null && file.superTypes().contains(fqnType);
    }

    /** Matches when at least one compile error message matches the given regex. */
    public static TriggerCondition compileErrorMatches(String regex) {
        var pattern = Pattern.compile(regex);
        return (inventory, file, errors) ->
                errors != null && errors.stream().anyMatch(e -> pattern.matcher(e).find());
    }

    /** Matches when the project inventory contains the given signal string. */
    public static TriggerCondition hasSignal(String signal) {
        return (inventory, file, errors) ->
                inventory.getSignals().contains(signal);
    }

    /**
     * AND-combines two conditions. Returns {@code true} only when both match.
     */
    public static TriggerCondition and(TriggerCondition a, TriggerCondition b) {
        return (inventory, file, errors) ->
                a.test(inventory, file, errors) && b.test(inventory, file, errors);
    }

    /**
     * OR-combines two conditions. Returns {@code true} when either matches.
     */
    public static TriggerCondition or(TriggerCondition a, TriggerCondition b) {
        return (inventory, file, errors) ->
                a.test(inventory, file, errors) || b.test(inventory, file, errors);
    }
}
