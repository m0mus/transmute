package io.transmute.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.classgraph.ClassGraph;
import io.transmute.migration.AiMigration;
import io.transmute.migration.MarkdownPostchecks;
import io.transmute.migration.MarkdownTrigger;
import io.transmute.migration.RecipeKind;
import io.transmute.migration.MigrationScope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
                        t.signals(),
                        t.compileErrors(),
                        t.files()));
            }
        }

        // Build postchecks
        MarkdownPostchecks postchecks;
        if (fm.postchecks() != null) {
            postchecks = new MarkdownPostchecks(
                    fm.postchecks().forbidImports(),
                    fm.postchecks().forbidPatterns(),
                    fm.postchecks().requireTodos());
        } else {
            postchecks = MarkdownPostchecks.empty();
        }

        // Scope — defaults to FILE
        var scope = "project".equalsIgnoreCase(fm.scope()) ? MigrationScope.PROJECT : MigrationScope.FILE;

        // Transforms — declares FQN ownership for features
        List<String> transformAnnotations = List.of();
        List<String> transformTypes = List.of();
        if (fm.transforms() != null) {
            transformAnnotations = fm.transforms().annotations();
            transformTypes = fm.transforms().types();
        }

        return new AiMigration(
                fm.name(),
                fm.order() > 0 ? fm.order() : 50,
                fm.after() != null ? fm.after() : List.of(),
                triggers,
                postchecks,
                recipeKind,
                scope,
                transformAnnotations,
                transformTypes,
                body);
    }

    /**
     * Checks that no two features claim the same annotation or type FQN.
     * Recipes are exempt — they are exclusive by class-type matching.
     */
    private void checkFeatureConflicts(List<AiMigration> aiMigrations) {
        var annotationOwners = new HashMap<String, List<String>>();
        var typeOwners = new HashMap<String, List<String>>();

        for (var aiMigration : aiMigrations) {
            if (aiMigration.skillType() != RecipeKind.FEATURE) {
                continue;
            }
            for (var ann : aiMigration.transformAnnotations()) {
                annotationOwners.computeIfAbsent(ann, k -> new ArrayList<>()).add(aiMigration.skillName());
            }
            for (var type : aiMigration.transformTypes()) {
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
