package io.transmute.migration.postcheck;

import io.transmute.migration.FileChange;
import io.transmute.migration.MarkdownPostchecks;
import io.transmute.migration.MigrationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostcheckRunnerTest {

    private final PostcheckRunner runner = new PostcheckRunner();

    @Test
    void passesWhenForbidRequireAndPatternConstraintsAreSatisfied() {
        var postchecks = new MarkdownPostchecks(
                List.of("javax.ws.rs."),
                List.of("io.helidon.webserver.http.Http"),
                List.of("DW_MIGRATION_TODO\\[manual\\]"),
                List.of());
        var change = FileChange.of(
                "src/main/java/acme/Foo.java",
                "",
                """
                import io.helidon.webserver.http.Http;
                public class Foo {}
                """);
        var result = MigrationResult.success(List.of(change), List.of(), "ok");

        var failures = runner.runMarkdownPostchecks(postchecks, result);
        assertTrue(failures.isEmpty());
    }

    @Test
    void failsWhenRequiredImportIsMissing() {
        var postchecks = new MarkdownPostchecks(
                List.of(),
                List.of("io.helidon.webserver.http.Http"),
                List.of(),
                List.of());
        var change = FileChange.of(
                "src/main/java/acme/Foo.java",
                "",
                "public class Foo {}");
        var result = MigrationResult.success(List.of(change), List.of(), "ok");

        var failures = runner.runMarkdownPostchecks(postchecks, result);
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("Required import missing"));
    }

    @Test
    void failsWhenForbiddenImportOrPatternExists() {
        var postchecks = new MarkdownPostchecks(
                List.of("javax.ws.rs."),
                List.of(),
                List.of("DW_MIGRATION_TODO\\[manual\\]"),
                List.of());
        var change = FileChange.of(
                "src/main/java/acme/Foo.java",
                "",
                """
                import javax.ws.rs.Path;
                // DW_MIGRATION_TODO[manual]
                public class Foo {}
                """);
        var result = MigrationResult.success(List.of(change), List.of(), "ok");

        var failures = runner.runMarkdownPostchecks(postchecks, result);
        assertEquals(2, failures.size());
    }
}
