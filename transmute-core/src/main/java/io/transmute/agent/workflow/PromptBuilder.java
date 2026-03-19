package io.transmute.agent.workflow;

import io.transmute.migration.AiMigration;

import java.util.List;
import java.util.stream.Stream;

/**
 * Builds AI system prompts from migration plan data.
 * Keeps prompt-composition logic out of the execution classes.
 */
class PromptBuilder {

    static final String JOURNAL_FILE = "migration-journal.md";

    private final String projectSummary;

    PromptBuilder(String projectSummary) {
        this.projectSummary = projectSummary;
    }

    /**
     * Builds the system prompt for a project-scoped migration.
     */
    String buildProjectScoped(AiMigration migration) {
        return migration.systemPromptSection()
                + (projectSummary.isBlank() ? "" : "\n\n## Project Context\n" + projectSummary);
    }

    /**
     * Builds a combined system prompt for one or more file-scoped migrations
     * to be applied to a single file in a single agent invocation.
     */
    String buildCombined(List<AiMigration> aiMigrations) {
        var sb = new StringBuilder();
        sb.append("""
                You are an expert Java developer executing a framework migration.
                Apply ALL sections below. Each section declares what it owns and what transformations to apply.
                Do not modify anything not covered by a section below.

                ## Universal Fallback Rule
                If any construct cannot be converted to the target framework, DO NOT leave broken
                or uncompilable code. Instead comment it out and annotate it:

                  // TRANSMUTE[unsupported]: <why this cannot be converted>

                For multi-line blocks:
                  /* TRANSMUTE[unsupported]: <description>
                  <original code>
                  */

                Use category `manual` for constructs the developer must convert manually,
                `unsupported` when no equivalent exists in the target framework,
                `recheck` when the conversion is uncertain and needs review.
                This rule applies to every recipe and feature section below.

                ## Migration Journal
                After completing your changes, append a brief summary line to \
                """)
          .append(JOURNAL_FILE)
          .append("""
                 using the append_file tool.
                Include: what migration(s) you applied, which file you changed, and any \
                decisions or edge cases worth noting for subsequent migrations or fix agents.
                """);

        if (!projectSummary.isBlank()) {
            sb.append("\n## Project Context\n").append(projectSummary).append("\n");
        }

        for (var migration : aiMigrations) {
            sb.append("\n## ").append(migration.skillName());
            var owned = Stream.concat(
                    migration.ownsAnnotations().stream(),
                    migration.ownsTypes().stream()).toList();
            if (!owned.isEmpty()) {
                sb.append(" (owns: ").append(String.join(", ", owned)).append(")");
            }
            sb.append("\n");

            var doNotTouch = aiMigrations.stream()
                    .filter(other -> other != migration)
                    .flatMap(other -> Stream.concat(
                            other.ownsAnnotations().stream(),
                            other.ownsTypes().stream()))
                    .distinct().toList();
            if (!doNotTouch.isEmpty()) {
                sb.append("DO NOT touch: ").append(String.join(", ", doNotTouch))
                  .append(" (handled by other sections)\n");
            }
            sb.append(migration.systemPromptSection()).append("\n");
        }
        return sb.toString();
    }
}
