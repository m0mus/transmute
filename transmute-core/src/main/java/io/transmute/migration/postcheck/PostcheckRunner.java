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

    /** Matches {@code /* ... *}{@code /} block comments (including multi-line). */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    /**
     * Strips block comments from {@code s} so that postchecks do not fire on
     * imports or patterns that the migration agent commented out via
     * {@code /* TRANSMUTE[...]: ... *}{@code /} markers.
     */
    private static String stripBlockComments(String s) {
        return s == null ? null : BLOCK_COMMENT.matcher(s).replaceAll("");
    }

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
                var effective = stripBlockComments(change.after());
                if (effective != null && effective.contains("import " + forbidImport)) {
                    return PostcheckResult.fail("Forbidden import still present: " + forbidImport);
                }
                return PostcheckResult.pass();
            });
        }
        for (var requireImport : postchecks.requireImports()) {
            rules.add(change -> {
                var effective = stripBlockComments(change.after());
                if (effective == null || !effective.contains("import " + requireImport)) {
                    return PostcheckResult.fail("Required import missing: " + requireImport);
                }
                return PostcheckResult.pass();
            });
        }
        for (var forbidPattern : postchecks.forbidPatterns()) {
            var compiled = Pattern.compile(forbidPattern);
            rules.add(change -> {
                var effective = stripBlockComments(change.after());
                if (effective != null && compiled.matcher(effective).find()) {
                    return PostcheckResult.fail("Forbidden pattern found: " + forbidPattern);
                }
                return PostcheckResult.pass();
            });
        }
        for (var requirePattern : postchecks.requirePatterns()) {
            var compiled = Pattern.compile(requirePattern);
            rules.add(change -> {
                var effective = stripBlockComments(change.after());
                if (effective == null || !compiled.matcher(effective).find()) {
                    return PostcheckResult.fail("Required pattern missing: " + requirePattern);
                }
                return PostcheckResult.pass();
            });
        }
        return rules;
    }
}
