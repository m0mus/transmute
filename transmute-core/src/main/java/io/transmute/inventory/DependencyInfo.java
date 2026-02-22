package io.transmute.inventory;

/**
 * Maven dependency coordinates with optional scope.
 */
public record DependencyInfo(
        String groupId,
        String artifactId,
        String version,
        String scope
) {}
