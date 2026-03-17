package io.transmute.migration;

/**
 * Distinguishes between recipe-style and feature-style AI migrations.
 *
 * <p>RECIPE migrations handle one class type and are exclusive — a file matches at most one recipe.
 * FEATURE migrations handle cross-cutting concerns and declare their FQN scope for conflict detection.
 */
public enum RecipeKind {
    RECIPE,
    FEATURE
}
