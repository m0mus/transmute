package io.transmute.migration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiMigrationScopeTest {

    @Test
    void derivesFileScopeFromFilesTrigger() {
        var migration = new AiMigration(
                "Build file migration",
                1,
                List.of(new MarkdownTrigger(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("pom.xml"),
                        List.of())),
                MarkdownPostchecks.empty(),
                RecipeKind.FEATURE,
                List.of(),
                List.of(),
                "prompt");

        assertEquals(MigrationScope.FILE, migration.skillScope());
    }

    @Test
    void derivesProjectScopeWhenNoFileTargetingTriggerExists() {
        var migration = new AiMigration(
                "Global migration",
                1,
                List.of(new MarkdownTrigger(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())),
                MarkdownPostchecks.empty(),
                RecipeKind.FEATURE,
                List.of(),
                List.of(),
                "prompt");

        assertEquals(MigrationScope.PROJECT, migration.skillScope());
    }
}
