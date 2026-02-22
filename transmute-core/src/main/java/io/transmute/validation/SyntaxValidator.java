package io.transmute.validation;

import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates Java source syntax by parsing with OpenRewrite's JavaParser.
 */
public class SyntaxValidator {

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failed(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    public ValidationResult validate(String javaSource) {
        var errors = new ArrayList<String>();
        var ctx = new InMemoryExecutionContext(e -> errors.add(e.getMessage()));

        try {
            var parser = JavaParser.fromJavaVersion()
                    .logCompilationWarningsAndErrors(false)
                    .build();

            var sources = parser.parse(ctx, javaSource).toList();

            if (sources.isEmpty()) {
                return ValidationResult.failed(
                        List.of("Failed to parse source -- no compilation unit produced"));
            }

            var sourceFile = sources.getFirst();
            if (sourceFile instanceof J.CompilationUnit) {
                if (!errors.isEmpty()) {
                    return ValidationResult.failed(errors);
                }
                return ValidationResult.ok();
            }

            return ValidationResult.failed(
                    List.of("Unexpected parse result type: " + sourceFile.getClass().getSimpleName()));
        } catch (Exception e) {
            return ValidationResult.failed(List.of("Parse error: " + e.getMessage()));
        }
    }
}
