package io.transmute.agent;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the Transmute migration agent.
 * Populated from CLI arguments and/or environment variables.
 */
public record TransmuteConfig(
        String projectDir,
        String outputDir,
        String modelProvider,
        String modelId,
        String apiKey,
        String baseUrl,
        Integer modelTimeoutSeconds,
        boolean forceHttp1,
        String ociProfile,
        boolean verbose,
        boolean dryRun,
        List<String> activeProfiles
) {

    public TransmuteConfig {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
    }

    public static TransmuteConfig defaults() {
        return new TransmuteConfig(
                null, null,
                env("TRANSMUTE_MODEL_PROVIDER", "oci-genai"),
                env("TRANSMUTE_MODEL_ID", null),
                env("TRANSMUTE_API_KEY", null),
                env("TRANSMUTE_MODEL_BASE_URL", null),
                envInt("TRANSMUTE_MODEL_TIMEOUT_SECONDS", null),
                envBool("TRANSMUTE_FORCE_HTTP1", false),
                env("OCI_PROFILE", "DEFAULT"),
                envBool("TRANSMUTE_VERBOSE", false),
                envBool("TRANSMUTE_DRY_RUN", false),
                List.of()
        );
    }

    public String modelId(String defaultId) {
        return modelId != null ? modelId : defaultId;
    }

    public String baseUrl(String defaultUrl) {
        return baseUrl != null ? baseUrl : defaultUrl;
    }

    public Duration modelTimeout(Duration defaultTimeout) {
        if (modelTimeoutSeconds == null || modelTimeoutSeconds <= 0) {
            return defaultTimeout;
        }
        return Duration.ofSeconds(modelTimeoutSeconds);
    }

    /**
     * Parse CLI arguments into a TransmuteConfig.
     *
     * <pre>
     *   --project-dir &lt;path&gt;
     *   --output-dir &lt;path&gt;
     *   --model-provider &lt;name&gt;
     *   --model-id &lt;id&gt;
     *   --api-key &lt;key&gt;
     *   --base-url &lt;url&gt;
     *   --model-timeout-seconds &lt;seconds&gt;
     *   --oci-profile &lt;name&gt;
     *   --verbose
     *   --dry-run
     *   --profile &lt;name&gt;         (repeatable Maven profile)
     * </pre>
     */
    public static TransmuteConfig fromArgs(String[] args) {
        var d = defaults();
        String projectDir = null;
        String outputDir = null;
        String modelProvider = d.modelProvider();
        String modelId = d.modelId();
        String apiKey = d.apiKey();
        String baseUrl = d.baseUrl();
        Integer modelTimeoutSeconds = d.modelTimeoutSeconds();
        boolean forceHttp1 = d.forceHttp1();
        String ociProfile = d.ociProfile();
        boolean verbose = d.verbose();
        boolean dryRun = d.dryRun();
        var activeProfiles = new java.util.ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--project-dir"         -> { if (i + 1 < args.length) projectDir = args[++i]; }
                case "--output-dir"          -> { if (i + 1 < args.length) outputDir = args[++i]; }
                case "--model-provider"      -> { if (i + 1 < args.length) modelProvider = args[++i]; }
                case "--model-id"            -> { if (i + 1 < args.length) modelId = args[++i]; }
                case "--api-key"             -> { if (i + 1 < args.length) apiKey = args[++i]; }
                case "--base-url"            -> { if (i + 1 < args.length) baseUrl = args[++i]; }
                case "--model-timeout-seconds" -> {
                    if (i + 1 < args.length) modelTimeoutSeconds = parseIntOrDefault(args[++i], null);
                }
                case "--force-http1"         -> forceHttp1 = true;
                case "--oci-profile"         -> { if (i + 1 < args.length) ociProfile = args[++i]; }
                case "--profile"             -> { if (i + 1 < args.length) activeProfiles.add(args[++i]); }
                case "--verbose"             -> verbose = true;
                case "--dry-run"             -> dryRun = true;
            }
        }

        if (projectDir != null && outputDir == null) {
            outputDir = projectDir + "-transmuted";
        }

        return new TransmuteConfig(projectDir, outputDir, modelProvider, modelId, apiKey, baseUrl,
                modelTimeoutSeconds, forceHttp1,
                ociProfile, verbose, dryRun, activeProfiles);
    }

    private static String env(String name, String defaultValue) {
        var value = System.getenv(name);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private static boolean envBool(String name, boolean defaultValue) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("y");
    }

    private static Integer envInt(String name, Integer defaultValue) {
        return parseIntOrDefault(System.getenv(name), defaultValue);
    }

    private static Integer parseIntOrDefault(String value, Integer defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
