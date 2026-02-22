package io.transmute.skill.postcheck;

import io.transmute.skill.FileChange;
import io.transmute.skill.MigrationSkill;
import io.transmute.skill.SkillResult;
import io.transmute.skill.annotation.Postchecks;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads {@link Postchecks} from a skill class via reflection and verifies
 * each {@link PostcheckRule} against every {@link FileChange} in a {@link SkillResult}.
 */
public class PostcheckRunner {

    /**
     * Runs all postchecks declared on the skill and returns any failures.
     *
     * @param skill  the skill whose {@code @Postchecks} annotation to read
     * @param result the skill result to validate
     * @return list of failure messages; empty when all checks pass
     */
    public List<String> run(MigrationSkill skill, SkillResult result) {
        var ann = skill.getClass().getAnnotation(Postchecks.class);
        if (ann == null) {
            return List.of();
        }

        var rules = buildRules(ann, result);
        var failures = new ArrayList<String>();

        for (var change : result.changes()) {
            for (var rule : rules) {
                var check = rule.check(change);
                if (!check.passed()) {
                    failures.add("[" + change.file() + "] " + check.message());
                }
            }
        }

        return failures;
    }

    private List<PostcheckRule> buildRules(Postchecks ann, SkillResult result) {
        var rules = new ArrayList<PostcheckRule>();

        for (var forbidImport : ann.forbidImports()) {
            rules.add(change -> {
                var after = change.after();
                if (after != null && after.contains("import " + forbidImport)) {
                    return PostcheckResult.fail("Forbidden import still present: " + forbidImport);
                }
                return PostcheckResult.pass();
            });
        }

        for (var forbidPattern : ann.forbidPatterns()) {
            var compiled = Pattern.compile(forbidPattern);
            rules.add(change -> {
                var after = change.after();
                if (after != null && compiled.matcher(after).find()) {
                    return PostcheckResult.fail("Forbidden pattern found: " + forbidPattern);
                }
                return PostcheckResult.pass();
            });
        }

        for (var requireTodo : ann.requireTodos()) {
            rules.add(change -> {
                var todos = result.todos();
                boolean found = todos.stream().anyMatch(t -> t.contains(requireTodo));
                if (!found) {
                    return PostcheckResult.fail("Required TODO not present: " + requireTodo);
                }
                return PostcheckResult.pass();
            });
        }

        return rules;
    }
}
