package io.transmute.migration;


import java.util.List;

/**
 * Bridge interface that exposes recipe/feature metadata to the planner and runner infrastructure.
 *
 * <p>Planners and runners check {@code instanceof AiMigrationMetadata} before falling back to
 * annotation-based reflection. This lets recipes and features ({@link AiMigration} instances)
 * participate in trigger evaluation, topological ordering, postcheck validation, and
 * feature conflict detection without requiring Java annotations.
 */
public interface AiMigrationMetadata {

    /** Human-readable name (used for display and name-based {@code after} resolution). */
    String skillName();

    /** Execution order (lower runs first). */
    int skillOrder();

    /**
     * Names of Java migrations, recipes, or features that must run before this one.
     * Resolved by name at planning time.
     */
    List<String> skillAfterNames();

    /** Trigger conditions that determine when this recipe or feature applies. */
    List<MarkdownTrigger> skillTriggers();

    /** Postcheck rules validated after this recipe or feature's transformations are applied. */
    MarkdownPostchecks skillPostchecks();

    /** Whether this is a RECIPE (one class type) or FEATURE (cross-cutting concern). */
    RecipeKind skillType();

    /**
     * Whether this migration runs once per project or once per matching file.
     * Defaults to {@link MigrationScope#FILE}.
     */
    default MigrationScope skillScope() { return MigrationScope.FILE; }

    /**
     * FQN annotation types this feature owns — used for conflict detection.
     * Empty for recipes (which are exclusive by class-type matching and need no conflict check).
     */
    List<String> transformAnnotations();

    /**
     * FQN types this feature owns — used for conflict detection.
     * Empty for recipes.
     */
    List<String> transformTypes();

    /**
     * The markdown body of this recipe or feature's instruction section.
     * Inserted verbatim into the combined prompt sent to the AI agent.
     */
    String systemPromptSection();
}
