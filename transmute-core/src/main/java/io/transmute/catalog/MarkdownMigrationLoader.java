package io.transmute.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.classgraph.ClassGraph;
import io.transmute.migration.AiMigration;
import io.transmute.migration.MarkdownPostchecks;
import io.transmute.migration.MarkdownTrigger;
import io.transmute.migration.RecipeKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans the classpath for recipes ({@code recipes/*.recipe.md}) and features
 * ({@code features/*.feature.md}) and parses each into an {@link AiMigration}.
 *
 * <p>Feature conflict detection runs after all files are loaded: if two features declare
 * the same annotation or type FQN in their {@code transforms} block, a
 * {@link FeatureConflictException} is thrown before any migration can start.
 */
public class MarkdownMigrationLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * Loads all recipes and features from the classpath and validates features for conflicts.
     *
     * @return list of parsed {@link AiMigration} instances (unordered; the planner sorts them)
     * @throws FeatureConflictException if two features claim overlapping FQN ownership
     */
    public List<AiMigration> load() {
        var aiMigrations = new ArrayList<AiMigration>();

        try (var scanResult = new ClassGraph()
                .acceptPaths("recipes", "features")
                .scan()) {

            for (var resource : scanResult.getAllResources()) {
                var path = resource.getPath();
                if (!path.endsWith(".recipe.md") && !path.endsWith(".feature.md")) {
                    continue;
                }
                try (var stream = resource.open()) {
                    var content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    var aiMigration = parse(path, content);
                    if (aiMigration != null) {
                        aiMigrations.add(aiMigration);
                    }
                } catch (Exception e) {
                    System.err.println("[MarkdownMigrationLoader] Failed to load " + path + ": " + e.getMessage());
                }
            }
        }

        checkFeatureConflicts(aiMigrations);
        return aiMigrations;
    }

    /**
     * Parses a single recipe or feature file. Package-private for testing.
     *
     * @param resourcePath path label used in error messages
     * @param content      raw file content (including {@code ---} front-matter delimiters)
     * @return parsed {@link AiMigration}, or {@code null} if the file is malformed
     */
    AiMigration parse(String resourcePath, String content) {
        // Split on --- delimiters: [0]=preamble (empty), [1]=front-matter, [2]=body
        var parts = content.split("---", 3);
        if (parts.length < 3) {
            System.err.println("[MarkdownMigrationLoader] Invalid format in " + resourcePath
                    + ": expected front-matter delimited by ---");
            return null;
        }

        var frontMatter = parts[1].trim();
        var body = parts[2].trim();

        RecipeFrontMatter fm;
        try {
            fm = YAML.readValue(frontMatter, RecipeFrontMatter.class);
        } catch (Exception e) {
            System.err.println("[MarkdownMigrationLoader] Failed to parse front-matter in "
                    + resourcePath + ": " + e.getMessage());
            return null;
        }

        if (fm.name() == null || fm.name().isBlank()) {
            System.err.println("[MarkdownMigrationLoader] Missing required field 'name' in " + resourcePath);
            return null;
        }
        if (fm.type() == null || fm.type().isBlank()) {
            System.err.println("[MarkdownMigrationLoader] Missing required field 'type' in " + resourcePath);
            return null;
        }

        var recipeKind = switch (fm.type().toLowerCase()) {
            case "recipe"  -> RecipeKind.RECIPE;
            case "feature" -> RecipeKind.FEATURE;
            default -> {
                System.err.println("[MarkdownMigrationLoader] Unknown type '" + fm.type()
                        + "' in " + resourcePath + " (expected 'recipe' or 'feature')");
                yield null;
            }
        };
        if (recipeKind == null) {
            return null;
        }

        // Build triggers
        var triggers = new ArrayList<MarkdownTrigger>();
        if (fm.triggers() != null) {
            for (var t : fm.triggers()) {
                triggers.add(new MarkdownTrigger(
                        t.imports(),
                        t.annotations(),
                        t.superTypes(),
                        t.files()));
            }
        }

        // Build postchecks
        MarkdownPostchecks postchecks;
        if (fm.postchecks() != null) {
            postchecks = new MarkdownPostchecks(
                    fm.postchecks().forbidImports(),
                    fm.postchecks().requireImports(),
                    fm.postchecks().forbidPatterns(),
                    fm.postchecks().requirePatterns());
        } else {
            postchecks = MarkdownPostchecks.empty();
        }

        // Owns — auto-inherit annotations from all trigger groups, then apply explicit owns block
        var inheritedAnnotations = triggers.stream()
                .flatMap(t -> t.annotations().stream())
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        var owns = fm.owns();
        List<String> explicitAnnotations = owns != null ? owns.annotations()        : List.of();
        List<String> explicitTypes       = owns != null ? owns.types()              : List.of();
        List<String> excludeAnnotations  = owns != null ? owns.excludeAnnotations() : List.of();
        List<String> excludeTypes        = owns != null ? owns.excludeTypes()       : List.of();

        var ownsAnnotations = Stream.concat(inheritedAnnotations.stream(), explicitAnnotations.stream())
                .distinct()
                .filter(a -> !excludeAnnotations.contains(a))
                .toList();
        var ownsTypes = explicitTypes.stream()
                .filter(t -> !excludeTypes.contains(t))
                .toList();

        return new AiMigration(
                fm.name(),
                fm.order() > 0 ? fm.order() : 50,
                triggers,
                postchecks,
                recipeKind,
                ownsAnnotations,
                ownsTypes,
                body);
    }

    public record Hints(String compileHints, String testHints) {}

    /**
     * Loads converter-contributed agent hints from {@code hints/compile-hints.md} and
     * {@code hints/test-hints.md} on the classpath. Content from all JARs is concatenated.
     */
    public Hints loadHints() {
        var compile = new StringBuilder();
        var test    = new StringBuilder();
        try (var scan = new ClassGraph().acceptPaths("hints").scan()) {
            for (var r : scan.getAllResources()) {
                var path = r.getPath();
                try {
                    var content = new String(r.load(), StandardCharsets.UTF_8);
                    if (path.endsWith("compile-hints.md")) compile.append(content).append("\n");
                    if (path.endsWith("test-hints.md"))    test.append(content).append("\n");
                } catch (java.io.IOException e) {
                    System.err.println("[MarkdownMigrationLoader] Failed to load hints " + path + ": " + e.getMessage());
                }
            }
        }
        return new Hints(compile.toString().trim(), test.toString().trim());
    }

    public record Catalog(Map<String, DependencyCatalogEntry> entries) {}

    /**
     * Loads dependency catalog entries from {@code catalog/*.yml} files on the classpath.
     * Content from all JARs is merged; later entries override earlier ones for the same coordinate.
     */
    public Catalog loadCatalog() {
        var entries = new LinkedHashMap<String, DependencyCatalogEntry>();
        try (var scan = new ClassGraph().acceptPaths("catalog").scan()) {
            for (var r : scan.getAllResources()) {
                var path = r.getPath();
                if (!path.endsWith(".yml") && !path.endsWith(".yaml")) continue;
                try {
                    var list = YAML.readValue(r.load(),
                            new TypeReference<List<DependencyCatalogEntry>>() {});
                    for (var e : list) {
                        entries.put(e.groupId() + ":" + e.artifactId(), e);
                    }
                } catch (Exception e) {
                    System.err.println("[MarkdownMigrationLoader] Failed to load catalog "
                            + path + ": " + e.getMessage());
                }
            }
        }
        return new Catalog(Map.copyOf(entries));
    }

    /**
     * Checks that no two features claim the same annotation or type FQN.
     * Recipes are exempt — they are exclusive by class-type matching.
     */
    void checkFeatureConflicts(List<AiMigration> aiMigrations) {
        var annotationOwners = new HashMap<String, List<String>>();
        var typeOwners = new HashMap<String, List<String>>();

        for (var aiMigration : aiMigrations) {
            if (aiMigration.skillType() != RecipeKind.FEATURE) {
                continue;
            }
            for (var ann : aiMigration.ownsAnnotations()) {
                annotationOwners.computeIfAbsent(ann, k -> new ArrayList<>()).add(aiMigration.skillName());
            }
            for (var type : aiMigration.ownsTypes()) {
                typeOwners.computeIfAbsent(type, k -> new ArrayList<>()).add(aiMigration.skillName());
            }
        }

        var conflicts = new ArrayList<String>();
        for (var entry : annotationOwners.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add("annotation '" + entry.getKey() + "' claimed by: " + entry.getValue());
            }
        }
        for (var entry : typeOwners.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add("type '" + entry.getKey() + "' claimed by: " + entry.getValue());
            }
        }

        if (!conflicts.isEmpty()) {
            throw new FeatureConflictException(
                    "Feature FQN conflicts — fix before migration can run:\n  "
                    + String.join("\n  ", conflicts));
        }
    }
}
