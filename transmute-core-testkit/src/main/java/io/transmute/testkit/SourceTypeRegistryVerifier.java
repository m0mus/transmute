package io.transmute.testkit;

import io.transmute.catalog.SourceTypeRegistry;
import org.junit.jupiter.api.Assertions;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JUnit 5 helper that verifies {@link SourceTypeRegistry} implementations.
 *
 * <p>Example usage in a test:
 * <pre>{@code
 *   @Test
 *   void myRegistryIsValid() {
 *       SourceTypeRegistryVerifier.verify(new MySourceTypeRegistry())
 *           .expectMatch("io.dropwizard.Application")
 *           .expectNoMatch("java.lang.String")
 *           .run();
 *   }
 * }</pre>
 */
public class SourceTypeRegistryVerifier {

    private final SourceTypeRegistry registry;
    private final List<String> expectMatch;
    private final List<String> expectNoMatch;

    private SourceTypeRegistryVerifier(
            SourceTypeRegistry registry,
            List<String> expectMatch,
            List<String> expectNoMatch) {
        this.registry = registry;
        this.expectMatch = expectMatch;
        this.expectNoMatch = expectNoMatch;
    }

    public static Builder verify(SourceTypeRegistry registry) {
        return new Builder(registry);
    }

    public static final class Builder {
        private final SourceTypeRegistry registry;
        private final List<String> expectMatch = new java.util.ArrayList<>();
        private final List<String> expectNoMatch = new java.util.ArrayList<>();

        Builder(SourceTypeRegistry registry) {
            this.registry = registry;
        }

        public Builder expectMatch(String fqn) {
            expectMatch.add(fqn);
            return this;
        }

        public Builder expectNoMatch(String fqn) {
            expectNoMatch.add(fqn);
            return this;
        }

        public SourceTypeRegistryVerifier build() {
            return new SourceTypeRegistryVerifier(registry, List.copyOf(expectMatch), List.copyOf(expectNoMatch));
        }

        public void run() {
            build().run();
        }
    }

    /**
     * Runs all checks and fails the test on the first violation.
     */
    public void run() {
        // All patterns must be valid regex
        for (var pattern : registry.sourceTypePatterns()) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                Assertions.fail("Invalid regex pattern in " + registry.getClass().getSimpleName()
                        + ": '" + pattern + "' -- " + e.getMessage());
            }
        }

        for (var fqn : expectMatch) {
            Assertions.assertTrue(registry.isSourceType(fqn),
                    "Expected " + registry.getClass().getSimpleName()
                            + ".isSourceType(\"" + fqn + "\") to return true");
        }

        for (var fqn : expectNoMatch) {
            Assertions.assertFalse(registry.isSourceType(fqn),
                    "Expected " + registry.getClass().getSimpleName()
                            + ".isSourceType(\"" + fqn + "\") to return false");
        }
    }
}
