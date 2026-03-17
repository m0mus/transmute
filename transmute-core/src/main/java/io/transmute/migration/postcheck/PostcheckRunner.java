package io.transmute.migration.postcheck;

import io.transmute.migration.FileChange;
import io.transmute.migration.MarkdownPostchecks;
import io.transmute.migration.MigrationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a {@link MigrationResult} against the postchecks declared on a recipe or feature.
 */
public class PostcheckRunner {

    public List<String> runMarkdownPostchecks(MarkdownPostchecks postchecks, MigrationResult result) {
        if (postchecks == null) {
            return List.of();
        }
        var rules = buildRules(postchecks);
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

    private List<PostcheckRule> buildRules(MarkdownPostchecks postchecks) {
        var rules = new ArrayList<PostcheckRule>();
        for (var forbidImport : postchecks.forbidImports()) {
            rules.add(change -> {
                if (change.after() != null && change.after().contains("import " + forbidImport)) {
                    return PostcheckResult.fail("Forbidden import still present: " + forbidImport);
                }
                return PostcheckResult.pass();
            });
        }
        for (var requireImport : postchecks.requireImports()) {
            rules.add(change -> {
                if (change.after() == null || !change.after().contains("import " + requireImport)) {
                    return PostcheckResult.fail("Required import missing: " + requireImport);
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
        return rules;
    }
}
