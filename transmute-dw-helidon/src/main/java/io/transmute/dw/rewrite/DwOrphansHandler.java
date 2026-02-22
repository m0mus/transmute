package io.transmute.dw.rewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic handler for Dropwizard constructs that have no Helidon equivalent.
 *
 * <p>Applies three strategies:
 * <ul>
 *   <li><b>DW_POJO</b>: Strips {@code extends}/{@code implements} for DW base types;
 *       keeps all fields, methods, constructors; adds a class-level TODO comment.</li>
 *   <li><b>DW_COMMENT</b>: Comments out the import line and usages of DW types that
 *       have no Helidon equivalent (e.g. metrics, auth annotations); adds inline TODO.</li>
 *   <li><b>DW_REMOVE</b>: Silently removes imports and registration calls that are pure
 *       DW infrastructure plumbing with no user-code value.</li>
 * </ul>
 *
 * <p>Works on raw source text (no AST dependency). Safe to run multiple times (idempotent).
 */
public class DwOrphansHandler {

    /**
     * Simple class names for DW_POJO treatment, mapped to a human-readable hint
     * explaining what manual work remains.
     */
    private static final Map<String, String> POJO_TYPES = Map.of(
            "View",          "Was extends/implements View - implement templating manually (e.g. Freemarker, Mustache via plain Java)",
            "AbstractDAO",   "Was extends AbstractDAO - inject EntityManager or SessionFactory directly",
            "Managed",       "Was implements Managed - register start()/stop() with Helidon lifecycle",
            "Authenticator", "Was implements Authenticator - implement Helidon Security authenticator",
            "Authorizer",    "Was implements Authorizer - implement Helidon Security authorizer",
            "UnauthorizedHandler", "Was implements UnauthorizedHandler - implement Helidon Security handler"
    );

    /**
     * Import prefixes whose types should be commented out (DW_COMMENT).
     * Any usage of the simple class name in the file is also commented out.
     */
    private static final List<String> COMMENT_IMPORT_PREFIXES = List.of(
            "io.dropwizard.auth.Auth",                // @Auth parameter annotation
            "io.dropwizard.hibernate.UnitOfWork",     // @UnitOfWork method annotation
            "io.dropwizard.auth.AuthDynamicFeature",
            "io.dropwizard.auth.AuthFilter",
            "io.dropwizard.setup.Environment",
            "io.dropwizard.jersey.setup.JerseyEnvironment",
            "com.codahale.metrics.Counter",
            "com.codahale.metrics.Timer",
            "com.codahale.metrics.Meter",
            "com.codahale.metrics.Histogram",
            "com.codahale.metrics.Gauge",
            "com.codahale.metrics.MetricRegistry",
            // Security annotations from javax/jakarta that came transitively via Jersey
            "javax.annotation.security.PermitAll",
            "javax.annotation.security.RolesAllowed",
            "jakarta.annotation.security.PermitAll",
            "jakarta.annotation.security.RolesAllowed",
            // JAX-RS SecurityContext -- no Helidon equivalent at this type level
            "javax.ws.rs.core.SecurityContext",
            "jakarta.ws.rs.core.SecurityContext"
    );

    /**
     * Import prefixes to remove silently (DW_REMOVE) -- pure plumbing, no TODO needed.
     */
    private static final List<String> REMOVE_IMPORT_PREFIXES = List.of(
            "io.dropwizard.Bundle",
            "io.dropwizard.ConfiguredBundle"
    );

    public record HandlerResult(int filesModified, List<String> modifiedPaths) {}

    /**
     * Walk all {@code .java} files under {@code projectDir} and apply DW_POJO,
     * DW_COMMENT, and DW_REMOVE transformations where applicable.
     *
     * @param projectDir root directory of the (already-copied) output project
     * @return summary of how many files were modified
     */
    public HandlerResult handle(Path projectDir) {
        var modifiedPaths = new ArrayList<String>();
        List<Path> javaFiles = listJavaFiles(projectDir);

        for (var file : javaFiles) {
            try {
                String original = Files.readString(file);
                String transformed = transform(original);
                if (!transformed.equals(original)) {
                    Files.writeString(file, transformed);
                    modifiedPaths.add(projectDir.relativize(file).toString());
                }
            } catch (IOException e) {
                System.err.println("[DwOrphansHandler] Failed to process " + file + ": " + e.getMessage());
            }
        }

        return new HandlerResult(modifiedPaths.size(), modifiedPaths);
    }

    /**
     * Apply all transformations to a single source file's content.
     * Exposed package-private for unit testing.
     */
    String transform(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }

        var importedPojoTypes = findImportedPojoTypes(source);
        var importedRemoveTypes = findImportedRemoveTypes(source);
        boolean hasAnyCommentType = COMMENT_IMPORT_PREFIXES.stream().anyMatch(source::contains);

        if (importedPojoTypes.isEmpty() && !hasAnyCommentType && importedRemoveTypes.isEmpty()) {
            return source;
        }

        String result = source;

        // DW_POJO: strip extends/implements, keep class body
        for (var entry : importedPojoTypes.entrySet()) {
            String simpleName = entry.getKey();
            String hint = entry.getValue();
            result = applyPojoStrip(result, simpleName, hint);
        }

        // DW_COMMENT: comment out import + field/param/annotation usages
        for (var prefix : COMMENT_IMPORT_PREFIXES) {
            if (!source.contains(prefix)) {
                continue;
            }
            String simpleName = prefix.substring(prefix.lastIndexOf('.') + 1);
            result = applyCommentOut(result, simpleName, prefix);
        }

        // DW_REMOVE: silently remove import lines
        for (String fullImport : importedRemoveTypes) {
            result = removeImportLine(result, fullImport);
        }

        return result;
    }

    // DW_POJO

    private String applyPojoStrip(String source, String simpleName, String hint) {
        var result = source;

        result = result.replaceAll(
                "(?m)^import\\s+io\\.dropwizard\\.[^;]*\\b" + simpleName + ";\\s*$\\R?", "");

        result = result.replaceAll(
                "(?m)(public\\s+(?:(?:abstract|final|sealed)\\s+)*class\\s+\\w+(?:<[^>]*>)?(?:\\s+implements[^{]*)?)"
                        + "\\s+extends\\s+" + simpleName + "(?:<[^>]*>)?",
                addPojoTodo(hint) + "$1");

        result = result.replaceAll(
                "(?m)(public\\s+(?:(?:abstract|final|sealed)\\s+)*class\\s+\\w+(?:<[^>]*>)?(?:\\s+extends\\s+\\w+(?:<[^>]*>)?)?)"
                        + "\\s+implements\\s+" + simpleName + "(?:<[^>]*>)?\\s*(\\{)",
                addPojoTodo(hint) + "$1 $2");

        result = result.replaceAll(
                "(?m)\\bimplements\\s+" + simpleName + "(?:<[^>]*>)?\\s*,\\s*",
                "implements ");

        result = result.replaceAll(
                "(?m),\\s*" + simpleName + "(?:<[^>]*>)?(?=\\s*[,{])",
                "");

        return result;
    }

    private static String addPojoTodo(String hint) {
        return "// DW_MIGRATION_TODO[pojo]: " + hint + ".\n// The data and logic are preserved as plain Java. Manual integration with Helidon is required.\n";
    }

    // DW_COMMENT

    private String applyCommentOut(String source, String simpleName, String fullImport) {
        var result = source;

        result = result.replaceAll(
                "(?m)^(import\\s+" + escapeRegex(fullImport) + "(?:\\.\\*)?;)\\s*$",
                "// DW_MIGRATION_TODO[removed]: $1");

        result = result.replaceAll(
                "(?m)^([ \\t]*(?:private|protected|public)?\\s*(?:static\\s*)?(?:final\\s*)?)"
                        + simpleName + "(?:<[^>]*>)?\\s+(\\w+\\s*;)",
                "// DW_MIGRATION_TODO[manual]: " + simpleName + " has no Helidon equivalent - replace with Helidon metrics/security API\n"
                        + "// $1" + simpleName + " $2");

        // NOTE: replacement must NOT contain "@Auth" to avoid idempotency issues
        if ("Auth".equals(simpleName)) {
            result = result.replaceAll(
                    "@Auth\\b",
                    "/* DW_MIGRATION_TODO[manual]: auth annotation removed - implement Helidon Security */");
        }

        // NOTE: replacement must NOT contain "@UnitOfWork" for the same reason
        if ("UnitOfWork".equals(simpleName)) {
            result = result.replaceAll(
                    "(?m)^([ \\t]*)@UnitOfWork\\s*$",
                    "$1// DW_MIGRATION_TODO[manual]: UnitOfWork annotation removed - manage transactions manually in Helidon");
            result = result.replaceAll(
                    "(?m)^([ \\t]*)@UnitOfWork\\s*\\([^)]*\\)\\s*$",
                    "$1// DW_MIGRATION_TODO[manual]: UnitOfWork annotation removed - manage transactions manually in Helidon");
        }

        if ("PermitAll".equals(simpleName)) {
            result = result.replaceAll(
                    "(?m)^([ \\t]*)@PermitAll\\s*$",
                    "$1// DW_MIGRATION_TODO[manual]: PermitAll annotation removed - implement Helidon Security");
        }

        if ("RolesAllowed".equals(simpleName)) {
            result = result.replaceAll(
                    "(?m)^([ \\t]*)@RolesAllowed\\s*\\([^)]*\\)\\s*$",
                    "$1// DW_MIGRATION_TODO[manual]: RolesAllowed annotation removed - implement Helidon Security");
            result = result.replaceAll(
                    "(?m)^([ \\t]*)@RolesAllowed\\s*$",
                    "$1// DW_MIGRATION_TODO[manual]: RolesAllowed annotation removed - implement Helidon Security");
        }

        if ("SecurityContext".equals(simpleName)) {
            result = result.replaceAll(
                    "\\bSecurityContext\\s+(\\w+)",
                    "Object /* DW_MIGRATION_TODO[manual]: Was SecurityContext - implement Helidon Security context */ $1");
        }

        return result;
    }

    // DW_REMOVE

    private String removeImportLine(String source, String fullImport) {
        return source.replaceAll(
                "(?m)^import\\s+" + escapeRegex(fullImport) + "(?:\\.\\*)?;\\s*$\\R?", "");
    }

    // Import detection

    private Map<String, String> findImportedPojoTypes(String source) {
        var found = new java.util.LinkedHashMap<String, String>();
        for (var entry : POJO_TYPES.entrySet()) {
            var simpleName = entry.getKey();
            if (source.contains("import io.dropwizard.")
                    && source.matches("(?s).*import\\s+io\\.dropwizard\\.[^;]*\\b" + simpleName + ";.*")) {
                found.put(simpleName, entry.getValue());
            }
        }
        return found;
    }

    private List<String> findImportedRemoveTypes(String source) {
        var found = new ArrayList<String>();
        for (var prefix : REMOVE_IMPORT_PREFIXES) {
            if (source.contains(prefix)) {
                found.add(prefix);
            }
        }
        return found;
    }

    // File utilities

    private List<Path> listJavaFiles(Path projectDir) {
        var files = new ArrayList<Path>();
        try {
            Files.walk(projectDir)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .forEach(files::add);
        } catch (IOException e) {
            System.err.println("[DwOrphansHandler] Failed to list Java files: " + e.getMessage());
        }
        return files;
    }

    private static String escapeRegex(String s) {
        return s.replace(".", "\\.").replace("*", "\\*").replace("$", "\\$");
    }
}
