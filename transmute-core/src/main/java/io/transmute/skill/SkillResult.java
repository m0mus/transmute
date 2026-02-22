package io.transmute.skill;

import java.util.List;

/**
 * The outcome of executing a {@link MigrationSkill}.
 */
public record SkillResult(
        List<FileChange> changes,
        List<String> todos,
        boolean success,
        String message
) {

    public SkillResult {
        changes = List.copyOf(changes);
        todos = List.copyOf(todos);
    }

    public static SkillResult noChange() {
        return new SkillResult(List.of(), List.of(), true, "no change");
    }

    public static SkillResult failure(String msg) {
        return new SkillResult(List.of(), List.of(), false, msg);
    }

    public static SkillResult success(List<FileChange> changes, List<String> todos, String message) {
        return new SkillResult(changes, todos, true, message);
    }
}
