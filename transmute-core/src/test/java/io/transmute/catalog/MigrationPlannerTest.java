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

        var plan = new MigrationPlanner().plan(List.of(migration), inventory, List.of());
        assertEquals(1, plan.entries().size());
        assertEquals(1, plan.entries().getFirst().targetFiles().size());
        assertEquals("pom.xml", normalize(plan.entries().getFirst().targetFiles().getFirst()));
    }

    @Test
    void keepsProjectScopedMigrationWithoutTargetFiles() {
        var inventory = emptyInventory(tempDir);
        inventory.addSignal("global.signal");

        var migration = migration(
                "Global Migration",
                10,
                List.of(new MarkdownTrigger(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("global.signal"),
                        List.of(),
                        List.of())));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory, List.of());
        assertEquals(1, plan.entries().size());
        assertTrue(plan.entries().getFirst().targetFiles().isEmpty());
    }

    @Test
    void sortsByOrderThenName() {
        var inventory = emptyInventory(tempDir);

        var m1 = migration("Zeta", 20, List.of());
        var m2 = migration("Alpha", 10, List.of());
        var m3 = migration("Beta", 10, List.of());

        var plan = new MigrationPlanner().plan(List.of(m1, m2, m3), inventory, List.of());
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
                        List.of(),
                        List.of())));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory, List.of());
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
                        List.of(),
                        List.of(),
                        List.of("pom.xml"))));

        var plan = new MigrationPlanner().plan(List.of(migration), inventory, List.of());
        var targets = plan.entries().getFirst().targetFiles().stream().map(this::normalize).toList();
        assertEquals(2, targets.size());
        assertTrue(targets.contains("pom.xml"));
        assertTrue(targets.contains("src/main/java/acme/Foo.java"));
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
                List.of(),
                List.of(),
                List.of(paths));
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
