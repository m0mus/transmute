package io.transmute.agent.workflow;

import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.catalog.MigrationPlan;
import io.transmute.catalog.MigrationPlanner;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.Migration;

import java.util.List;

/**
 * Steps 4–5 (partial): discover migrations + build {@link MigrationPlan}.
 */
class PlanningService {

    private final MarkdownMigrationLoader loader;

    PlanningService(MarkdownMigrationLoader loader) {
        this.loader = loader;
    }

    List<Migration> discoverMigrations(int stepNum, int totalSteps) {
        Out.step(stepNum, totalSteps, "Discovering migrations");
        List<Migration> migrations = java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(loader.load()));
        Out.info("Found " + Out.bold(migrations.size() + " migrations"));
        Out.rule();
        return migrations;
    }

    MigrationPlan buildPlan(List<Migration> migrations, ProjectInventory inventory) {
        return new MigrationPlanner().plan(migrations, inventory);
    }
}
