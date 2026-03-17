package io.transmute.migration;

import java.util.List;

/**
 * Parsed trigger condition from a recipe or feature's front-matter.
 *
 * <p>Mirrors the semantics of {@link io.transmute.migration.annotation.Trigger}:
 * AND within a trigger (all non-empty arrays must match), OR across triggers.
 */
public record MarkdownTrigger(
        List<String> imports,
        List<String> annotations,
        List<String> superTypes,
        List<String> signals,
        List<String> compileErrors,
        List<String> files) {

    public MarkdownTrigger {
        imports = imports != null ? List.copyOf(imports) : List.of();
        annotations = annotations != null ? List.copyOf(annotations) : List.of();
        superTypes = superTypes != null ? List.copyOf(superTypes) : List.of();
        signals = signals != null ? List.copyOf(signals) : List.of();
        compileErrors = compileErrors != null ? List.copyOf(compileErrors) : List.of();
        files = files != null ? List.copyOf(files) : List.of();
    }
}
