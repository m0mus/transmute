package io.transmute.catalog;

import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigration;
import io.transmute.migration.MarkdownPostchecks;
import io.transmute.migration.MarkdownTrigger;
import io.transmute.migration.RecipeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationPlannerTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesPomXmlAsFileTargetFromFilesTrigger() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        var inventory = emptyInventory(tempDir);

        var migration = migration(
                "Build File Migration",
                1,
                List.of(triggerFiles("pom.xml")));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory);
        assertEquals(1, plan.entries().size());
        assertEquals(1, plan.entries().getFirst().targetFiles().size());
        assertEquals("pom.xml", normalize(plan.entries().getFirst().targetFiles().getFirst()));
    }

    @Test
    void projectScopedMigrationWithNoTriggersIsAlwaysIncluded() {
        var inventory = emptyInventory(tempDir);

        var migration = migration("Global Migration", 10, List.of());

        var plan = new MigrationPlanner().plan(List.of(migration), inventory);
        assertEquals(1, plan.entries().size());
        assertTrue(plan.entries().getFirst().targetFiles().isEmpty());
    }

    @Test
    void sortsByOrderThenName() {
        var inventory = emptyInventory(tempDir);

        var m1 = migration("Zeta", 20, List.of());
        var m2 = migration("Alpha", 10, List.of());
        var m3 = migration("Beta", 10, List.of());

        var plan = new MigrationPlanner().plan(List.of(m1, m2, m3), inventory);
        var names = plan.entries().stream().map(e -> e.migration().name()).toList();
        assertEquals(List.of("Alpha", "Beta", "Zeta"), names);
    }

    @Test
    void dropsFileScopedMigrationWhenNoTargetsMatch() {
        var inventory = emptyInventory(tempDir);
        var migration = migration(
                "REST Resource",
                5,
                List.of(new MarkdownTrigger(
                        List.of("javax.ws.rs."),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory);
        assertTrue(plan.entries().isEmpty());
    }

    @Test
    void resolvesJavaAndNonJavaTargetsWhenTriggerContainsBoth() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        var inventory = emptyInventory(tempDir);
        inventory.setJavaFiles(List.of(new JavaFileInfo(
                "src/main/java/acme/Foo.java",
                "acme.Foo",
                Set.of(),
                Set.of("javax.ws.rs.Path"),
                Set.of(),
                Map.of())));

        var migration = migration(
                "Mixed Trigger",
                1,
                List.of(new MarkdownTrigger(
                        List.of("javax.ws.rs."),
                        List.of(),
                        List.of(),
                        List.of("pom.xml"),
                        List.of())));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory);
        var targets = plan.entries().getFirst().targetFiles().stream().map(this::normalize).toList();
        assertEquals(2, targets.size());
        assertTrue(targets.contains("pom.xml"));
        assertTrue(targets.contains("src/main/java/acme/Foo.java"));
    }

    @Test
    void multipleRecipesTargetingSameFileAreBothInPlan() {
        var inventory = emptyInventory(tempDir);
        inventory.setJavaFiles(List.of(new JavaFileInfo(
                "src/main/java/acme/Bar.java",
                "acme.Bar",
                Set.of(),
                Set.of("com.example.SomeImport"),
                Set.of(),
                Map.of())));

        var triggerA = new MarkdownTrigger(List.of("com.example."), List.of(), List.of(), List.of(), List.of());
        var triggerB = new MarkdownTrigger(List.of("com.example."), List.of(), List.of(), List.of(), List.of());

        var recipeA = new AiMigration("Recipe A", 1, List.of(triggerA), MarkdownPostchecks.empty(), RecipeKind.RECIPE, List.of(), List.of(), "prompt");
        var recipeB = new AiMigration("Recipe B", 5, List.of(triggerB), MarkdownPostchecks.empty(), RecipeKind.RECIPE, List.of(), List.of(), "prompt");

        var plan = new MigrationPlanner().plan(List.of(recipeB, recipeA), inventory);
        assertEquals(2, plan.entries().size());
        assertEquals("Recipe A", plan.entries().get(0).migration().name());
        assertEquals("Recipe B", plan.entries().get(1).migration().name());
        assertTrue(plan.entries().get(0).targetFiles().stream().map(this::normalize).toList()
                .contains("src/main/java/acme/Bar.java"));
        assertTrue(plan.entries().get(1).targetFiles().stream().map(this::normalize).toList()
                .contains("src/main/java/acme/Bar.java"));
    }

    @Test
    void featureAndRecipeTargetingSameFileAreBothInPlan() {
        var inventory = emptyInventory(tempDir);
        inventory.setJavaFiles(List.of(new JavaFileInfo(
                "src/main/java/acme/Baz.java",
                "acme.Baz",
                Set.of(),
                Set.of("org.shared.SharedImport"),
                Set.of(),
                Map.of())));

        var trigger = new MarkdownTrigger(List.of("org.shared."), List.of(), List.of(), List.of(), List.of());

        var recipe = new AiMigration("My Recipe", 1, List.of(trigger), MarkdownPostchecks.empty(), RecipeKind.RECIPE, List.of(), List.of(), "prompt");
        var feature = new AiMigration("My Feature", 2, List.of(trigger), MarkdownPostchecks.empty(), RecipeKind.FEATURE, List.of(), List.of(), "prompt");

        var plan = new MigrationPlanner().plan(List.of(feature, recipe), inventory);
        assertEquals(2, plan.entries().size());
        var names = plan.entries().stream().map(e -> e.migration().name()).toList();
        assertTrue(names.contains("My Recipe"));
        assertTrue(names.contains("My Feature"));
        plan.entries().forEach(e ->
                assertTrue(e.targetFiles().stream().map(this::normalize).toList()
                        .contains("src/main/java/acme/Baz.java")));
    }

    @Test
    void excludeImportsPreventsTriggerMatchForMatchingFile() {
        var inventory = emptyInventory(tempDir);
        inventory.setJavaFiles(List.of(new JavaFileInfo(
                "src/test/java/acme/FooTest.java",
                "acme.FooTest",
                Set.of(),
                Set.of("javax.ws.rs.core.Response", "io.dropwizard.testing.junit5.ResourceExtension"),
                Set.of(),
                Map.of())));

        // trigger matches javax.ws.rs. but excludes io.dropwizard.testing — file should not match
        var migration = migration(
                "REST Resource",
                5,
                List.of(new MarkdownTrigger(
                        List.of("javax.ws.rs."),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("io.dropwizard.testing"))));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory);
        assertTrue(plan.entries().isEmpty());
    }

    @Test
    void excludeImportsDoesNotAffectNonExcludedFile() {
        var inventory = emptyInventory(tempDir);
        inventory.setJavaFiles(List.of(new JavaFileInfo(
                "src/main/java/acme/FooResource.java",
                "acme.FooResource",
                Set.of(),
                Set.of("javax.ws.rs.Path", "javax.ws.rs.GET"),
                Set.of(),
                Map.of())));

        var migration = migration(
                "REST Resource",
                5,
                List.of(new MarkdownTrigger(
                        List.of("javax.ws.rs."),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("io.dropwizard.testing"))));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory);
        assertEquals(1, plan.entries().size());
        assertTrue(plan.entries().getFirst().targetFiles().stream().map(this::normalize).toList()
                .contains("src/main/java/acme/FooResource.java"));
    }

    @Test
    void superTypesMigrationExcludedWhenNoFileMatchesSuperType() {
        var inventory = emptyInventory(tempDir);
        inventory.setJavaFiles(List.of(new JavaFileInfo(
                "src/main/java/acme/NoMatch.java",
                "acme.NoMatch",
                Set.of(),
                Set.of(),
                Set.of("java.lang.Object"),
                Map.of())));

        var migration = migration(
                "Super Type Migration",
                3,
                List.of(new MarkdownTrigger(
                        List.of(),
                        List.of(),
                        List.of("com.example.Foo"),
                        List.of(),
                        List.of())));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory);
        assertTrue(plan.entries().isEmpty());
    }

    private ProjectInventory emptyInventory(Path root) {
        var inventory = new ProjectInventory();
        inventory.setRootDir(root.toAbsolutePath().normalize().toString());
        inventory.setJavaFiles(List.of());
        return inventory;
    }

    private AiMigration migration(String name, int order, List<MarkdownTrigger> triggers) {
        return new AiMigration(
                name,
                order,
                triggers,
                MarkdownPostchecks.empty(),
                RecipeKind.FEATURE,
                List.of(),
                List.of(),
                "prompt");
    }

    private MarkdownTrigger triggerFiles(String... paths) {
        return new MarkdownTrigger(
                List.of(),
                List.of(),
                List.of(),
                List.of(paths),
                List.of());
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
