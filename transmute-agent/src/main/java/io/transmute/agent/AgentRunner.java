package io.transmute.agent;

import io.transmute.agent.workflow.MigrationWorkflow;

/**
 * Main entry point for the Transmute migration agent.
 *
 * <pre>
 * Usage:
 *   java -jar transmute-agent.jar --project-dir /path/to/project [options]
 *
 * Required:
 *   --project-dir &lt;path&gt;         Path to the source project to migrate
 *
 * Options:
 *   --output-dir &lt;path&gt;          Output directory (default: &lt;project-dir&gt;-transmuted)
 *   --skills-package &lt;pkg&gt;       Package to scan for MigrationSkill impls (repeatable)
 *   --profile &lt;name&gt;             Maven profile to activate (repeatable)
 *   --model-provider &lt;name&gt;      AI model provider: oci-genai (default), openai, ollama
 *   --model-id &lt;id&gt;              Model ID (provider-specific default if not set)
 *   --api-key &lt;key&gt;              API key (for openai)
 *   --base-url &lt;url&gt;             Override endpoint URL (for ollama, proxies)
 *   --model-timeout-seconds &lt;n&gt;   Override chat request timeout in seconds (OpenAI-compatible)
 *   --force-http1                Force HTTP 1.1 for OpenAI-compatible local LLMs
 *   --oci-profile &lt;name&gt;         OCI config profile (default: DEFAULT)
 *   --auto-approve               Skip human approval gates (non-interactive)
 *   --verbose                    Verbose tool progress logging
 *   --dry-run                    Collect changes without writing files
 *   --allow-order-conflicts      Warn instead of fail on unresolved @Skill(after=...) deps
 * </pre>
 */
public class AgentRunner {

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
        }

        var config = MigrationConfig.fromArgs(args);

        if (config.projectDir() == null) {
            System.err.println("Error: --project-dir is required");
            printUsage();
            System.exit(1);
        }

        // Configure model provider
        ModelFactory.configure(config);

        System.out.println("Transmute Migration Agent");
        System.out.println("  Model provider:  " + config.modelProvider());
        System.out.println("  Model id:        " + resolvedModelId(config));
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            System.out.println("  Model base URL:  " + config.baseUrl());
        }
        if (config.forceHttp1()) {
            System.out.println("  Force HTTP 1.1:  yes");
        }
        System.out.println("  Project:         " + config.projectDir());
        System.out.println("  Output:          " + config.outputDir());
        System.out.println("  Skills packages: "
                + (config.skillsPackages().isEmpty() ? "(none)" : config.skillsPackages()));
        System.out.println("  Dry run:         " + (config.dryRun() ? "yes" : "no"));
        System.out.println("  Auto-approve:    " + (config.autoApprove() ? "yes" : "no"));
        System.out.println("  Verbose:         " + (config.verbose() ? "yes" : "no"));
        System.out.println();

        System.setProperty("transmute.verbose", String.valueOf(config.verbose()));

        try {
            var workflow = new MigrationWorkflow(config);
            workflow.run();
        } catch (Exception e) {
            var msg = e.getMessage();
            if (msg != null && (msg.contains("aborted by user")
                    || msg.contains("stopped at review"))) {
                System.out.println(msg);
            } else {
                System.err.println("Migration failed: " + msg);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    private static String resolvedModelId(MigrationConfig config) {
        return switch (config.modelProvider()) {
            case "oci-genai" -> config.modelId("cohere.command-r-plus");
            case "openai" -> config.modelId("gpt-4o");
            case "ollama" -> config.modelId("llama3.3");
            default -> config.modelId();
        };
    }

    private static void printUsage() {
        System.out.println("""
            Transmute Migration Agent

            Usage:
              java -jar transmute-agent.jar --project-dir <path> [options]

            Required:
              --project-dir <path>         Path to the source project to migrate

            Options:
              --output-dir <path>          Output directory (default: <project-dir>-transmuted)
              --skills-package <pkg>       Package to scan for MigrationSkill impls (repeatable)
              --profile <name>             Maven profile to activate (repeatable)
              --model-provider <name>      AI model provider: oci-genai (default), openai, ollama
              --model-id <id>              Model ID (provider-specific default if not set)
              --api-key <key>              API key (for openai)
              --base-url <url>             Override endpoint URL (for ollama, proxies)
              --model-timeout-seconds <n>  Chat request timeout in seconds (OpenAI-compatible)
              --force-http1               Force HTTP 1.1 (for local LLMs that don't support HTTP/2)
              --oci-profile <name>         OCI config profile (default: DEFAULT)
              --auto-approve               Skip human approval gates (non-interactive)
              --verbose                    Verbose tool progress logging
              --dry-run                    Collect changes without writing files
              --allow-order-conflicts      Warn instead of fail on unresolved ordering deps

            Environment variables:
              TRANSMUTE_MODEL_PROVIDER     Model provider name
              TRANSMUTE_MODEL_ID           Model ID
              TRANSMUTE_API_KEY            API key
              TRANSMUTE_MODEL_BASE_URL     Base URL override
              TRANSMUTE_MODEL_TIMEOUT_SECONDS  Chat request timeout in seconds
              TRANSMUTE_FORCE_HTTP1        true/false to force HTTP 1.1
              TRANSMUTE_AUTO_APPROVE       true/false to skip approvals
              TRANSMUTE_VERBOSE            true/false for tool progress logging
              TRANSMUTE_DRY_RUN            true/false for dry run mode
            """);
    }
}
