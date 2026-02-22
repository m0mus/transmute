package io.transmute.dw.skill;

import io.transmute.skill.FileChange;
import io.transmute.skill.MigrationSkill;
import io.transmute.skill.SkillContext;
import io.transmute.skill.SkillResult;
import io.transmute.skill.annotation.Postchecks;
import io.transmute.skill.annotation.Skill;
import io.transmute.skill.annotation.SkillScope;
import io.transmute.skill.annotation.Trigger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Migrates Dropwizard HealthCheck implementations to Helidon 4 health checks.
 *
 * <p>Transforms:
 * <ul>
 *   <li>{@code extends com.codahale.metrics.health.HealthCheck} ->
 *       {@code implements io.helidon.health.HealthCheck}</li>
 *   <li>{@code protected Result check()} -> {@code public io.helidon.health.HealthCheckResponse call()}</li>
 *   <li>{@code Result.healthy()} -> {@code io.helidon.health.HealthCheckResponse.builder().status(true).build()}</li>
 *   <li>{@code Result.unhealthy(msg)} -> {@code io.helidon.health.HealthCheckResponse.builder().status(false).detail("message", msg).build()}</li>
 *   <li>Removes old Codahale import, adds Helidon health import</li>
 * </ul>
 *
 * <p>FILE scope: only runs on files that extend the Dropwizard HealthCheck base class.
 */
@Skill(value = "HealthCheck Migration", order = 20, scope = SkillScope.FILE,
       after = {JaxrsAnnotationsSkill.class})
@Trigger(
    imports  = {"com.codahale.metrics.health.HealthCheck", "io.dropwizard.health.HealthCheck"},
    superTypes = {"com.codahale.metrics.health.HealthCheck", "io.dropwizard.health.HealthCheck"}
)
@Postchecks(forbidImports = {"com.codahale.metrics.health.HealthCheck"})
public class HealthCheckSkill implements MigrationSkill {

    @Override
    public SkillResult apply(SkillContext ctx) throws Exception {
        var targetFiles = ctx.targetFiles();
        if (targetFiles == null || targetFiles.isEmpty()) {
            return SkillResult.noChange();
        }

        var changes = new java.util.ArrayList<FileChange>();

        for (var filePath : targetFiles) {
            var file = Path.of(filePath);
            if (!Files.exists(file)) {
                continue;
            }
            String before = Files.readString(file);
            String after = transform(before);
            if (!before.equals(after)) {
                if (!ctx.workspace().isDryRun()) {
                    Files.writeString(file, after);
                }
                changes.add(new FileChange(filePath, before, after));
            }
        }

        if (changes.isEmpty()) {
            return SkillResult.noChange();
        }
        return SkillResult.success(changes, List.of(),
                "HealthCheck migrated in " + changes.size() + " file(s)");
    }

    /**
     * Apply all HealthCheck transformations to a single source file.
     * Package-private for unit testing.
     */
    String transform(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }

        // Guard: must contain codahale or dropwizard HealthCheck
        if (!source.contains("HealthCheck")) {
            return source;
        }

        String result = source;

        // Remove old imports
        result = result.replaceAll(
                "(?m)^import\\s+com\\.codahale\\.metrics\\.health\\.HealthCheck;\\s*$\\R?", "");
        result = result.replaceAll(
                "(?m)^import\\s+com\\.codahale\\.metrics\\.health\\.HealthCheck\\.Result;\\s*$\\R?", "");
        result = result.replaceAll(
                "(?m)^import\\s+io\\.dropwizard\\.health\\.HealthCheck;\\s*$\\R?", "");

        // Add Helidon health import after the package declaration (or at top if no package)
        if (!result.contains("import io.helidon.health.HealthCheck")) {
            result = result.replaceFirst(
                    "(?m)^(package\\s+[^;]+;\\s*\\R)",
                    "$1\nimport io.helidon.health.HealthCheck;\nimport io.helidon.health.HealthCheckResponse;\n");
        }

        // Change extends to implements
        result = result.replaceAll(
                "(?m)\\bextends\\s+(?:com\\.codahale\\.metrics\\.health\\.)?HealthCheck\\b",
                "implements HealthCheck");

        // Migrate check() method signature
        result = result.replaceAll(
                "(?m)^([ \\t]*)(?:protected|public)\\s+Result\\s+check\\s*\\(\\s*\\)\\s*(throws[^{]+)?\\{",
                "$1@Override\n$1public HealthCheckResponse call() {");

        // Migrate Result.healthy() -> builder pattern
        result = result.replaceAll(
                "\\bResult\\.healthy\\(\\)",
                "HealthCheckResponse.builder().status(true).build()");
        result = result.replaceAll(
                "\\bResult\\.healthy\\(([^)]+)\\)",
                "HealthCheckResponse.builder().status(true).detail(\"message\", $1).build()");

        // Migrate Result.unhealthy(msg) -> builder pattern
        result = result.replaceAll(
                "\\bResult\\.unhealthy\\(([^)]+)\\)",
                "HealthCheckResponse.builder().status(false).detail(\"message\", $1).build()");

        // Remove any remaining bare Result type references (e.g. local variable declarations)
        // Leave them as-is but add a TODO if still present
        if (result.contains("Result.")) {
            result = result.replaceAll(
                    "\\bResult\\.",
                    "HealthCheckResponse.builder()/* DW_MIGRATION_TODO: review Result usage */.");
        }

        return result;
    }
}
