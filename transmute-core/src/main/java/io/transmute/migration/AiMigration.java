package io.transmute.migration;


import java.util.List;

/**
 * A markdown-defined recipe or feature that delegates its transformation to an AI agent.
 *
 * <p>Holds the parsed front-matter and markdown body of a {@code .recipe.md} or
 * {@code .feature.md} file. Does NOT invoke an agent directly — {@code MigrationWorkflow}
 * groups all recipes and features targeting the same file into a single agent invocation
 * so all transformations are applied atomically.
 *
 * <p>{@link #apply(MigrationContext)} is kept for planner/postcheck compatibility but is not
 * the primary execution path — the workflow bypasses it and calls its own
 * {@code applyToFile} method instead.
 */
public class AiMigration implements Migration, AiMigrationMetadata {

    private final String name;
    private final int order;
    private final List<String> afterNames;
    private final List<MarkdownTrigger> triggers;
    private final MarkdownPostchecks postchecks;
    private final RecipeKind type;
    private final MigrationScope scope;
    private final List<String> transformAnnotations;
    private final List<String> transformTypes;
    private final String body;

    public AiMigration(
            String name,
            int order,
            List<String> afterNames,
            List<MarkdownTrigger> triggers,
            MarkdownPostchecks postchecks,
            RecipeKind type,
            MigrationScope scope,
            List<String> transformAnnotations,
            List<String> transformTypes,
            String body) {
        this.name = name;
        this.order = order;
        this.afterNames = afterNames != null ? List.copyOf(afterNames) : List.of();
        this.triggers = triggers != null ? List.copyOf(triggers) : List.of();
        this.postchecks = postchecks != null ? postchecks : MarkdownPostchecks.empty();
        this.type = type != null ? type : RecipeKind.RECIPE;
        this.scope = scope != null ? scope : MigrationScope.FILE;
        this.transformAnnotations = transformAnnotations != null ? List.copyOf(transformAnnotations) : List.of();
        this.transformTypes = transformTypes != null ? List.copyOf(transformTypes) : List.of();
        this.body = body != null ? body : "";
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Stub — real execution is handled by {@code MigrationWorkflow.applyToFile}.
     */
    @Override
    public MigrationResult apply(MigrationContext ctx) {
        return MigrationResult.noChange();
    }

    // ── AiMigrationMetadata ───────────────────────────────────────────────────

    @Override
    public String skillName() { return name; }

    @Override
    public int skillOrder() { return order; }

    @Override
    public List<String> skillAfterNames() { return afterNames; }

    @Override
    public List<MarkdownTrigger> skillTriggers() { return triggers; }

    @Override
    public MarkdownPostchecks skillPostchecks() { return postchecks; }

    @Override
    public RecipeKind skillType() { return type; }

    @Override
    public MigrationScope skillScope() { return scope; }

    @Override
    public List<String> transformAnnotations() { return transformAnnotations; }

    @Override
    public List<String> transformTypes() { return transformTypes; }

    @Override
    public String systemPromptSection() { return body; }
}
