package io.transmute.compile;

import io.transmute.catalog.SourceTypeRegistry;
import io.transmute.skill.MigrationSkill;

import java.util.List;
import java.util.Optional;

/**
 * Classifies {@link CompileError} instances using the resolved FQN and
 * {@link SourceTypeRegistry} data.
 */
public class CompileErrorAnalyzer {

    private final List<SourceTypeRegistry> registries;

    public CompileErrorAnalyzer(List<SourceTypeRegistry> registries) {
        this.registries = registries == null ? List.of() : List.copyOf(registries);
    }

    /**
     * Analyses a single compile error and returns a classified {@link ErrorAnalysis}.
     */
    public ErrorAnalysis analyze(CompileError error) {
        var resolvedFqn = error.resolvedFqn();

        // If FQN is from a known source-framework type -> SKILL_GAP
        if (resolvedFqn.isPresent()) {
            var fqn = resolvedFqn.get();
            boolean isSourceType = registries.stream().anyMatch(r -> r.isSourceType(fqn));
            if (isSourceType) {
                return new ErrorAnalysis(error, ErrorClass.SKILL_GAP, Optional.empty());
            }
        }

        // Heuristic: unresolved symbol without known FQN -> NOVEL unless it's a simple
        // compile-only issue (wrong type, wrong import in same project)
        var msg = error.message().toLowerCase();
        if (msg.contains("cannot find symbol") || msg.contains("package does not exist")) {
            if (resolvedFqn.isEmpty()) {
                return new ErrorAnalysis(error, ErrorClass.NOVEL, Optional.empty());
            }
            return new ErrorAnalysis(error, ErrorClass.COMPILE_ONLY, Optional.empty());
        }

        return new ErrorAnalysis(error, ErrorClass.COMPILE_ONLY, Optional.empty());
    }

    /**
     * Analyses a list of compile errors.
     */
    public List<ErrorAnalysis> analyzeAll(List<CompileError> errors) {
        return errors.stream().map(this::analyze).toList();
    }
}
