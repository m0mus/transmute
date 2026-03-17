package io.transmute.migration.postcheck;

import io.transmute.migration.AiMigrationMetadata;
import io.transmute.migration.FileChange;
import io.transmute.migration.MarkdownPostchecks;
import io.transmute.migration.Migration;
import io.transmute.migration.MigrationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a {@link MigrationResult} against the postchecks declared on an AI migration.
 */
public class PostcheckRunner {

    /**
     * Runs postchecks for the given migration. Only AI migrations ({@link AiMigrationMetadata})
     * declare postchecks; Java migrations validate their own output within {@code apply()}.
     */
    public List<String> run(Migration migration, MigrationResult result) {
        if (migration instanceof AiMigrationMetadata sm) {
            return runMarkdownPostchecks(sm.skillPostchecks(), result);
        }
        return List.of();
    }

    /**
     * Runs the postchecks declared in a recipe or feature against a migration result.
     * Exposed so {@code MigrationWorkflow} can run per-contributing-recipe postchecks
     * after a combined AI invocation.
     */
    public List<String> runMarkdownPostchecks(MarkdownPostchecks postchecks, MigrationResult result) {
        if (postchecks == null) {
            return List.of();
        }
        var rules = buildRules(postchecks, result);
        return applyRules(rules, result);
    }

    private List<String> applyRules(List<PostcheckRule> rules, MigrationResult result) {
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

    private List<PostcheckRule> buildRules(MarkdownPostchecks postchecks, MigrationResult result) {
        var rules = new ArrayList<PostcheckRule>();
        for (var forbidImport : postchecks.forbidImports()) {
            rules.add(change -> {
                if (change.after() != null && change.after().contains("import " + forbidImport)) {
                    return PostcheckResult.fail("Forbidden import still present: " + forbidImport);
                }
                return PostcheckResult.pass();
            });
        }
        for (var forbidPattern : postchecks.forbidPatterns()) {
            var compiled = Pattern.compile(forbidPattern);
            rules.add(change -> {
                if (change.after() != null && compiled.matcher(change.after()).find()) {
                    return PostcheckResult.fail("Forbidden pattern found: " + forbidPattern);
                }
                return PostcheckResult.pass();
            });
        }
        for (var requireTodo : postchecks.requireTodos()) {
            rules.add(change -> {
                boolean found = result.todos().stream().anyMatch(t -> t.contains(requireTodo));
                return found ? PostcheckResult.pass()
                        : PostcheckResult.fail("Required TODO not present: " + requireTodo);
            });
        }
        return rules;
    }
}
