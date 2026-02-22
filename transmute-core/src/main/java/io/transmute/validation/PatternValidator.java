package io.transmute.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks that configurable patterns are absent from (or present in) source code.
 *
 * <p>Unlike the DW-Converter version, this class carries no Dropwizard-specific
 * knowledge — patterns are supplied at construction time.
 */
public class PatternValidator {

    public record PatternCheckResult(boolean passed, List<String> findings) {
        public static PatternCheckResult ok() {
            return new PatternCheckResult(true, List.of());
        }

        public static PatternCheckResult failed(List<String> findings) {
            return new PatternCheckResult(false, findings);
        }
    }

    private record PatternCheck(Pattern pattern, String description) {}

    private final List<PatternCheck> forbidChecks;

    /** Creates a validator with no configured patterns (always passes). */
    public PatternValidator() {
        this.forbidChecks = List.of();
    }

    /**
     * Creates a validator with a custom set of forbidden patterns.
     *
     * @param forbiddenPatterns list of {@code (regex, description)} pairs
     */
    public PatternValidator(List<String[]> forbiddenPatterns) {
        this.forbidChecks = forbiddenPatterns.stream()
                .map(p -> new PatternCheck(Pattern.compile(p[0]), p[1]))
                .toList();
    }

    public PatternCheckResult validate(String source) {
        var findings = new ArrayList<String>();

        for (var check : forbidChecks) {
            var matcher = check.pattern().matcher(source);
            while (matcher.find()) {
                var lineNum = source.substring(0, matcher.start()).split("\n").length;
                findings.add("Line " + lineNum + ": " + check.description()
                        + " -- found: " + matcher.group());
            }
        }

        return findings.isEmpty()
                ? PatternCheckResult.ok()
                : PatternCheckResult.failed(findings);
    }
}
