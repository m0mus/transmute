package io.transmute.inventory;

import java.util.List;

/**
 * Metadata about a Maven module within a multi-module project.
 */
public record ModuleInfo(
        String artifactId,
        String modulePath,
        List<DependencyInfo> managedDependencies,
        List<String> dependsOn
) {
    public ModuleInfo {
        managedDependencies = List.copyOf(managedDependencies);
        dependsOn = List.copyOf(dependsOn);
    }
}
