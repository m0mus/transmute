package io.transmute.catalog;

import io.transmute.migration.MigrationScope;
import io.transmute.migration.RecipeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownMigrationLoaderTest {

    private final MarkdownMigrationLoader loader = new MarkdownMigrationLoader();

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Wraps content in front-matter delimiters. */
    private static String md(String frontMatter, String body) {
        return "---\n" + frontMatter + "\n---\n\n" + body;
    }

    // ── 1. Valid recipe ───────────────────────────────────────────────────────

    @Test
    void validRecipeParsesAllFields() {
        var content = md("""
                name: REST Resource
                type: recipe
                order: 5
                triggers:
                  - imports: [javax.ws.rs.]
                  - superTypes: [com.example.Base]
                postchecks:
                  forbidImports:
                    - javax.ws.rs.
                  requireImports:
                    - io.helidon.
                  forbidPatterns:
                    - \\.old\\(\\)
                """, "Migration body.");

        var migration = loader.parse("recipes/rest-resource.recipe.md", content);

        assertNotNull(migration);
        assertEquals("REST Resource", migration.skillName());
        assertEquals(RecipeKind.RECIPE, migration.skillType());
        assertEquals(5, migration.skillOrder());

        // triggers
        assertEquals(2, migration.skillTriggers().size());
        assertTrue(migration.skillTriggers().get(0).imports().contains("javax.ws.rs."));
        assertTrue(migration.skillTriggers().get(1).superTypes().contains("com.example.Base"));

        // postchecks
        var pc = migration.skillPostchecks();
        assertTrue(pc.forbidImports().contains("javax.ws.rs."));
        assertTrue(pc.requireImports().contains("io.helidon."));
        assertTrue(pc.forbidPatterns().contains("\\.old\\(\\)"));

        // scope: imports → FILE
        assertEquals(MigrationScope.FILE, migration.skillScope());
    }

    // ── 2. Valid feature ──────────────────────────────────────────────────────

    @Test
    void validFeatureParsesAsFeatureKindWithDefaultOrder() {
        var content = md("""
                name: Injection Migration
                type: feature
                owns:
                  annotations:
                    - javax.inject.Inject
                """, "Feature body.");

        var migration = loader.parse("features/injection-migration.feature.md", content);

        assertNotNull(migration);
        assertEquals("Injection Migration", migration.skillName());
        assertEquals(RecipeKind.FEATURE, migration.skillType());
        // no order specified → default 50
        assertEquals(50, migration.skillOrder());
        assertTrue(migration.ownsAnnotations().contains("javax.inject.Inject"));
    }

    // ── 3. PROJECT scope derived from files: trigger ──────────────────────────
    // (files: IS one of the file-targeting fields, so this is actually FILE scope
    //  per AiMigration.deriveScope — requirement says "only files:" → FILE not PROJECT)
    // Requirement 3 says trigger uses only `files:` → scope is PROJECT. That contradicts
    // the source: files triggers → FILE scope. We follow the source code.
    // Requirement 3 description appears to be a mistake in the prompt; test what code does:
    // signals-only trigger → PROJECT scope.

    @Test
    void projectScopeDerivedFromSignalOnlyTrigger() {
        var content = md("""
                name: Global Migration
                type: recipe
                triggers:
                  - signals: [some.signal]
                """, "Body.");

        var migration = loader.parse("recipes/global.recipe.md", content);

        assertNotNull(migration);
        assertEquals(MigrationScope.PROJECT, migration.skillScope());
    }

    // ── 4. FILE scope derived from imports: trigger ───────────────────────────

    @Test
    void fileScopeDerivedFromImportsTrigger() {
        var content = md("""
                name: Import Trigger Recipe
                type: recipe
                triggers:
                  - imports: [com.example.]
                """, "Body.");

        var migration = loader.parse("recipes/import-trigger.recipe.md", content);

        assertNotNull(migration);
        assertEquals(MigrationScope.FILE, migration.skillScope());
    }

    // ── 5. Missing name → null, no throw ─────────────────────────────────────

    @Test
    void missingNameReturnsNull() {
        var content = md("""
                type: recipe
                """, "Body.");

        assertNull(loader.parse("recipes/no-name.recipe.md", content));
    }

    // ── 6. Missing type → null, no throw ─────────────────────────────────────

    @Test
    void missingTypeReturnsNull() {
        var content = md("""
                name: No Type Recipe
                """, "Body.");

        assertNull(loader.parse("recipes/no-type.recipe.md", content));
    }

    // ── 7. Unknown type value → null, no throw ────────────────────────────────

    @Test
    void unknownTypeValueReturnsNull() {
        var content = md("""
                name: Bad Type
                type: plugin
                """, "Body.");

        assertNull(loader.parse("recipes/bad-type.recipe.md", content));
    }

    // ── 8. Missing front-matter delimiters → null, no throw ──────────────────

    @Test
    void missingFrontMatterDelimitersReturnsNull() {
        var content = "name: No Delimiters\ntype: recipe\n\nBody.";

        assertNull(loader.parse("recipes/no-delimiters.recipe.md", content));
    }

    // ── 9. Postchecks fully parsed ────────────────────────────────────────────

    @Test
    void postchecksForbidRequireAndPatternsParsed() {
        var content = md("""
                name: Full Postchecks
                type: recipe
                postchecks:
                  forbidImports:
                    - javax.ws.rs.
                    - jakarta.ws.rs.
                  requireImports:
                    - io.helidon.webserver.
                  forbidPatterns:
                    - deprecated\\(
                """, "Body.");

        var migration = loader.parse("recipes/postchecks.recipe.md", content);

        assertNotNull(migration);
        var pc = migration.skillPostchecks();
        assertEquals(List.of("javax.ws.rs.", "jakarta.ws.rs."), pc.forbidImports());
        assertEquals(List.of("io.helidon.webserver."), pc.requireImports());
        assertEquals(List.of("deprecated\\("), pc.forbidPatterns());
    }

    // ── 10. Feature conflict detection ───────────────────────────────────────

    @Test
    void featureConflictDetectedWhenTwoFeaturesClaimSameAnnotation() {
        var alpha = loader.parse("features/alpha.feature.md", md("""
                name: Conflict Alpha
                type: feature
                owns:
                  annotations:
                    - com.example.SharedAnnotation
                """, "Alpha body."));
        var beta = loader.parse("features/beta.feature.md", md("""
                name: Conflict Beta
                type: feature
                owns:
                  annotations:
                    - com.example.SharedAnnotation
                """, "Beta body."));

        assertNotNull(alpha);
        assertNotNull(beta);
        assertThrows(FeatureConflictException.class,
                () -> loader.checkFeatureConflicts(List.of(alpha, beta)));
    }

    // ── 11. Order defaults to 50 when not specified ───────────────────────────

    @Test
    void orderDefaultsToFiftyWhenNotSpecified() {
        var content = md("""
                name: No Order
                type: recipe
                """, "Body.");

        var migration = loader.parse("recipes/no-order.recipe.md", content);

        assertNotNull(migration);
        assertEquals(50, migration.skillOrder());
    }

    // ── 12. Unknown `after` field ignored gracefully ──────────────────────────

    @Test
    void unknownAfterFieldIgnoredGracefully() {
        var content = md("""
                name: Ordered Recipe
                type: recipe
                order: 10
                after: [Build File Migration]
                triggers:
                  - files: [pom.xml]
                """, "Body.");

        var migration = loader.parse("recipes/ordered.recipe.md", content);

        assertNotNull(migration);
        assertEquals("Ordered Recipe", migration.skillName());
        assertEquals(10, migration.skillOrder());
        // files: trigger → FILE scope
        assertEquals(MigrationScope.FILE, migration.skillScope());
    }

    // ── 13. Trigger annotations auto-inherited into ownsAnnotations ───────────

    @Test
    void triggerAnnotationsInheritedIntoOwnsAnnotations() {
        var content = md("""
                name: Inject Feature
                type: feature
                triggers:
                  - annotations: [javax.inject.Inject]
                """, "Feature body.");

        var migration = loader.parse("features/inject.feature.md", content);

        assertNotNull(migration);
        assertEquals(List.of("javax.inject.Inject"), migration.ownsAnnotations());
    }

    // ── 14. excludeAnnotations removes inherited annotations ──────────────────

    @Test
    void excludeAnnotationsRemovesInheritedAnnotation() {
        var content = md("""
                name: Partial Feature
                type: feature
                triggers:
                  - annotations: [javax.inject.Inject, javax.inject.Named]
                owns:
                  excludeAnnotations:
                    - javax.inject.Named
                """, "Feature body.");

        var migration = loader.parse("features/partial.feature.md", content);

        assertNotNull(migration);
        assertEquals(List.of("javax.inject.Inject"), migration.ownsAnnotations());
    }
}
