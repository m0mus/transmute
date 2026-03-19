package io.transmute.migration;


import java.util.List;

/**
 * A markdown-defined recipe or feature that delegates its transformation to an AI agent.
 *
 * <p>Holds the parsed front-matter and markdown body of a {@code .recipe.md} or
 * {@code .feature.md} file. Execution is handled entirely by {@code MigrationWorkflow},
 * which groups all recipes targeting the same file into a single agent invocation
 * so all transformations are applied atomically.
 */
public class AiMigration implements Migration, AiMigrationMetadata {

    private final String name;
    private final int order;
    private final List<MarkdownTrigger> triggers;
    private final MarkdownPostchecks postchecks;
    private final RecipeKind type;
    private final MigrationScope scope;
    private final List<String> ownsAnnotations;
    private final List<String> ownsTypes;
    private final String body;

    public AiMigration(
            String name,
            int order,
            List<MarkdownTrigger> triggers,
            MarkdownPostchecks postchecks,
            RecipeKind type,
            List<String> ownsAnnotations,
            List<String> ownsTypes,
            String body) {
        this.name = name;
        this.order = order;
        this.triggers = triggers != null ? List.copyOf(triggers) : List.of();
        this.postchecks = postchecks != null ? postchecks : MarkdownPostchecks.empty();
        this.type = type != null ? type : RecipeKind.RECIPE;
        this.scope = deriveScope(this.triggers);
        this.ownsAnnotations = ownsAnnotations != null ? List.copyOf(ownsAnnotations) : List.of();
        this.ownsTypes = ownsTypes != null ? List.copyOf(ownsTypes) : List.of();
        this.body = body != null ? body : "";
    }

    @Override
    public String name() {
        return name;
    }

    // ── AiMigrationMetadata ───────────────────────────────────────────────────

    @Override
    public String skillName() { return name; }

    @Override
    public int skillOrder() { return order; }

    @Override
    public List<MarkdownTrigger> skillTriggers() { return triggers; }

    @Override
    public MarkdownPostchecks skillPostchecks() { return postchecks; }

    @Override
    public RecipeKind skillType() { return type; }

    @Override
    public MigrationScope skillScope() { return scope; }

    @Override
    public List<String> ownsAnnotations() { return ownsAnnotations; }

    @Override
    public List<String> ownsTypes() { return ownsTypes; }

    @Override
    public String systemPromptSection() { return body; }

    private static MigrationScope deriveScope(List<MarkdownTrigger> triggers) {
        for (var trigger : triggers) {
            if (!trigger.imports().isEmpty()
                    || !trigger.annotations().isEmpty()
                    || !trigger.superTypes().isEmpty()
                    || !trigger.files().isEmpty()) {
                return MigrationScope.FILE;
            }
        }
        return MigrationScope.PROJECT;
    }
}
