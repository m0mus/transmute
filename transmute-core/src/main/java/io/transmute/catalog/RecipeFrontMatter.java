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
        int order,
        List<TriggerFrontMatter> triggers,
        OwnsFrontMatter owns,
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
            List<String> files,
            List<String> excludeImports) {

        public TriggerFrontMatter {
            imports = imports != null ? imports : List.of();
            annotations = annotations != null ? annotations : List.of();
            superTypes = superTypes != null ? superTypes : List.of();
            files = files != null ? files : List.of();
            excludeImports = excludeImports != null ? excludeImports : List.of();
        }
    }

    /** The {@code owns} block — declares FQN ownership for features. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OwnsFrontMatter(
            List<String> annotations,
            List<String> types,
            List<String> excludeAnnotations,
            List<String> excludeTypes) {

        public OwnsFrontMatter {
            annotations        = annotations        != null ? annotations        : List.of();
            types              = types              != null ? types              : List.of();
            excludeAnnotations = excludeAnnotations != null ? excludeAnnotations : List.of();
            excludeTypes       = excludeTypes       != null ? excludeTypes       : List.of();
        }
    }

    /** The {@code postchecks} block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostchecksFrontMatter(
            List<String> forbidImports,
            List<String> requireImports,
            List<String> forbidPatterns,
            List<String> requirePatterns) {

        public PostchecksFrontMatter {
            forbidImports   = forbidImports   != null ? forbidImports   : List.of();
            requireImports  = requireImports  != null ? requireImports  : List.of();
            forbidPatterns  = forbidPatterns  != null ? forbidPatterns  : List.of();
            requirePatterns = requirePatterns != null ? requirePatterns : List.of();
        }
    }
}
