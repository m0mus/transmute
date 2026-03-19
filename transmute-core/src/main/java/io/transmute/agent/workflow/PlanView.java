package io.transmute.agent.workflow;

import io.transmute.catalog.MigrationPlan;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigration;
import io.transmute.migration.RecipeKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Pre-computed, file-centric view of the migration plan.
 *
 * @param coveredFiles ordered list of all files targeted by at least one migration
 * @param fileRecipes  file → names of RECIPE-kind migrations that target it
 * @param fileFeatures file → names of FEATURE-kind migrations that target it
 * @param projectScoped names of project-scoped migrations (no target files)
 * @param totalSrc     source files in inventory (excludes test files)
 * @param totalTest    test files in inventory
 */
record PlanView(
        List<String> coveredFiles,
        Map<String, List<String>> fileRecipes,
        Map<String, List<String>> fileFeatures,
        List<String> projectScoped,
        int totalSrc,
        int totalTest
) {
    static PlanView build(MigrationPlan plan, ProjectInventory inventory) {
        var fileRecipes  = new LinkedHashMap<String, List<String>>();
        var fileFeatures = new LinkedHashMap<String, List<String>>();
        var projectScoped = new ArrayList<String>();

        for (var entry : plan.entries()) {
            var migration = (AiMigration) entry.migration();
            if (entry.targetFiles().isEmpty()) {
                projectScoped.add(migration.name());
            } else {
                for (var file : entry.targetFiles()) {
                    if (migration.skillType() == RecipeKind.RECIPE) {
                        fileRecipes.computeIfAbsent(file, k -> new ArrayList<>()).add(migration.name());
                    } else {
                        fileFeatures.computeIfAbsent(file, k -> new ArrayList<>()).add(migration.name());
                    }
                }
            }
        }

        var coveredSet = new LinkedHashSet<String>();
        fileRecipes.keySet().forEach(coveredSet::add);
        fileFeatures.keySet().forEach(coveredSet::add);

        int testFiles = (int) inventory.getJavaFiles().stream()
                .filter(f -> f.sourceFile().replace('\\', '/').contains("/test/"))
                .count();
        int srcFiles = inventory.getJavaFiles().size() - testFiles;

        return new PlanView(
                List.copyOf(coveredSet),
                fileRecipes,
                fileFeatures,
                List.copyOf(projectScoped),
                srcFiles,
                testFiles);
    }
}
