package io.transmute.agent.agent;

import java.util.List;

/**
 * Shared runtime configuration for AI agent tool instances.
 *
 * <p>Set by {@code MigrationWorkflow} before building each agent so that
 * {@code @ToolsSupplier} static methods can receive the output directory and
 * active profiles at agent-build time.
 */
public final class AgentToolsConfig {

    public static volatile String outputDir;
    public static volatile List<String> activeProfiles = List.of();

    private AgentToolsConfig() {}
}
