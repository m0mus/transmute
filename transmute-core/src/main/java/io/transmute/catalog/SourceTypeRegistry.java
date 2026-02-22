package io.transmute.catalog;

import java.util.List;

/**
 * Registry of FQN patterns that identify "source-framework" types
 * (types from the framework being migrated away from).
 *
 * <p>Implementations are discovered by {@link SkillDiscovery} and used by
 * {@link io.transmute.compile.CompileErrorParser} to enrich compile errors
 * with resolved FQNs.
 *
 * <p>Patterns use {@link String#matches(String)} semantics (fully-anchored regex).
 */
public interface SourceTypeRegistry {

    /**
     * Returns a list of fully-anchored regex patterns for source-framework FQNs.
     * For example: {@code "io\\.dropwizard\\..*"}.
     */
    List<String> sourceTypePatterns();

    /**
     * Returns {@code true} when {@code fqn} matches any registered pattern.
     */
    default boolean isSourceType(String fqn) {
        if (fqn == null) {
            return false;
        }
        return sourceTypePatterns().stream().anyMatch(fqn::matches);
    }
}
