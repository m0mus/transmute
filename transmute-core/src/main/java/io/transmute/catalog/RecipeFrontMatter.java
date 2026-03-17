package io.transmute.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * YAML front-matter deserialization model for {@code .recipe.md} and {@code .feature.md} files.
 *
 * <p>Parsed by {@link MarkdownMigrationLoader} using Jackson's YAML factory.
 * Unknown fields are ignored to allow forward-compatible recipe and feature files.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecipeFrontMatter(
        String name,
        String type,
        String scope,
        int order,
        List<String> after,
        List<TriggerFrontMatter> triggers,
        TransformsFrontMatter transforms,
        PostchecksFrontMatter postchecks) {

    /**
     * One entry in the {@code triggers} list.
     * Each entry is an AND-group; multiple entries are OR-combined.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TriggerFrontMatter(
            List<String> imports,
            List<String> annotations,
            List<String> superTypes,
            List<String> signals,
            List<String> compileErrors,
            List<String> files) {

        public TriggerFrontMatter {
            imports = imports != null ? imports : List.of();
            annotations = annotations != null ? annotations : List.of();
            superTypes = superTypes != null ? superTypes : List.of();
            signals = signals != null ? signals : List.of();
            compileErrors = compileErrors != null ? compileErrors : List.of();
            files = files != null ? files : List.of();
        }
    }

    /** The {@code transforms} block — declares FQN ownership for features. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransformsFrontMatter(
            List<String> annotations,
            List<String> types) {

        public TransformsFrontMatter {
            annotations = annotations != null ? annotations : List.of();
            types = types != null ? types : List.of();
        }
    }

    /** The {@code postchecks} block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostchecksFrontMatter(
            List<String> forbidImports,
            List<String> forbidPatterns,
            List<String> requireTodos) {

        public PostchecksFrontMatter {
            forbidImports = forbidImports != null ? forbidImports : List.of();
            forbidPatterns = forbidPatterns != null ? forbidPatterns : List.of();
            requireTodos = requireTodos != null ? requireTodos : List.of();
        }
    }
}
