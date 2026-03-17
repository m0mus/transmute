package io.transmute.migration;

import java.util.List;

/**
 * Parsed postcheck rules from a recipe or feature's front-matter.
 *
 * <p>Mirrors the semantics of {@link io.transmute.migration.annotation.Postchecks}.
 */
public record MarkdownPostchecks(
        List<String> forbidImports,
        List<String> forbidPatterns,
        List<String> requireTodos) {

    public MarkdownPostchecks {
        forbidImports = forbidImports != null ? List.copyOf(forbidImports) : List.of();
        forbidPatterns = forbidPatterns != null ? List.copyOf(forbidPatterns) : List.of();
        requireTodos = requireTodos != null ? List.copyOf(requireTodos) : List.of();
    }

    public static MarkdownPostchecks empty() {
        return new MarkdownPostchecks(List.of(), List.of(), List.of());
    }
}
