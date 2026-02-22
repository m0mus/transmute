package io.transmute.dw;

import io.transmute.catalog.SourceTypeRegistry;

import java.util.List;

/**
 * Identifies Dropwizard 3 and related JAX-RS / Codahale source-framework types.
 *
 * <p>Patterns are fully-anchored regex strings matched against FQN strings via
 * {@link String#matches(String)}.  The {@code .*} wildcard is used to cover
 * all sub-packages and inner classes within each framework namespace.
 */
public class DropwizardSourceTypeRegistry implements SourceTypeRegistry {

    private static final List<String> PATTERNS = List.of(
            // Dropwizard core, bundles, lifecycle, views, validation
            "io\\.dropwizard\\..*",
            // Codahale Metrics
            "com\\.codahale\\.metrics\\..*",
            // JAX-RS (javax and jakarta namespaces)
            "javax\\.ws\\.rs\\..*",
            "jakarta\\.ws\\.rs\\..*",
            // CDI / DI annotations used by Jersey
            "javax\\.inject\\..*",
            "jakarta\\.inject\\..*",
            // Jersey server runtime
            "org\\.glassfish\\.jersey\\..*",
            // Jetty (embedded by Dropwizard)
            "org\\.eclipse\\.jetty\\..*",
            // Hibernate Validator (Dropwizard validation bundle)
            "org\\.hibernate\\.validator\\..*",
            // Dropwizard param types
            "io\\.dropwizard\\.jersey\\.params\\..*",
            "io\\.dropwizard\\.jersey\\.jsr310\\..*"
    );

    @Override
    public List<String> sourceTypePatterns() {
        return PATTERNS;
    }
}
