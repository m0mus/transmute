package io.transmute.dw.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.RemoveUnusedImports;
import org.openrewrite.java.JavaParser;
import org.openrewrite.TreeVisitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies JaxrsToHelidonRecipe plus ChangeType recipes to all Java sources in a project directory.
 *
 * <p>This is the driver used by {@link io.transmute.dw.skill.JaxrsAnnotationsSkill}.
 * It also runs DwOrphansHandler for POJO/COMMENT/REMOVE transformations and applies
 * post-processing text transforms (Produces/Consumes, DW annotation TODOs, Views, etc.).
 */
public class JaxrsAnnotationsMigrator {

    public record ResultSummary(int totalFiles, int changedFiles, List<String> changedPaths) {}

    public ResultSummary apply(Path projectDir) {
        var javaFiles = listJavaFiles(projectDir);
        if (javaFiles.isEmpty()) {
            return new ResultSummary(0, 0, List.of());
        }

        var parser = JavaParser.fromJavaVersion()
                .dependsOn(JaxrsToHelidonRecipe.JAXRS_AND_HELIDON_STUBS)
                .build();
        ExecutionContext ctx = new InMemoryExecutionContext(err -> {
            if (err != null) {
                System.err.println("[jaxrs-migrator] " + err.getMessage());
            }
        });

        Recipe annotations = new JaxrsToHelidonRecipe();
        List<Recipe> annotationTypeChanges = List.of(
                new ChangeType("javax.ws.rs.GET",       "io.helidon.http.Http$GET",       true),
                new ChangeType("javax.ws.rs.POST",      "io.helidon.http.Http$POST",      true),
                new ChangeType("javax.ws.rs.PUT",       "io.helidon.http.Http$PUT",       true),
                new ChangeType("javax.ws.rs.DELETE",    "io.helidon.http.Http$DELETE",    true),
                new ChangeType("javax.ws.rs.PATCH",     "io.helidon.http.Http$PATCH",     true),
                new ChangeType("javax.ws.rs.HEAD",      "io.helidon.http.Http$HEAD",      true),
                new ChangeType("javax.ws.rs.OPTIONS",   "io.helidon.http.Http$OPTIONS",   true),
                new ChangeType("javax.ws.rs.Path",      "io.helidon.http.Http$Path",      true),
                new ChangeType("javax.ws.rs.PathParam",   "io.helidon.http.Http$PathParam",   true),
                new ChangeType("javax.ws.rs.QueryParam",  "io.helidon.http.Http$QueryParam",  true),
                new ChangeType("javax.ws.rs.HeaderParam", "io.helidon.http.Http$HeaderParam", true),
                new ChangeType("javax.inject.Inject",    "io.helidon.service.registry.Service$Inject",    true),
                new ChangeType("javax.inject.Singleton", "io.helidon.service.registry.Service$Singleton", true),
                new ChangeType("jakarta.ws.rs.GET",     "io.helidon.http.Http$GET",       true),
                new ChangeType("jakarta.ws.rs.POST",    "io.helidon.http.Http$POST",      true),
                new ChangeType("jakarta.ws.rs.PUT",     "io.helidon.http.Http$PUT",       true),
                new ChangeType("jakarta.ws.rs.DELETE",  "io.helidon.http.Http$DELETE",    true),
                new ChangeType("jakarta.ws.rs.PATCH",   "io.helidon.http.Http$PATCH",     true),
                new ChangeType("jakarta.ws.rs.HEAD",    "io.helidon.http.Http$HEAD",      true),
                new ChangeType("jakarta.ws.rs.OPTIONS", "io.helidon.http.Http$OPTIONS",   true),
                new ChangeType("jakarta.ws.rs.Path",    "io.helidon.http.Http$Path",      true),
                new ChangeType("jakarta.ws.rs.PathParam",   "io.helidon.http.Http$PathParam",   true),
                new ChangeType("jakarta.ws.rs.QueryParam",  "io.helidon.http.Http$QueryParam",  true),
                new ChangeType("jakarta.ws.rs.HeaderParam", "io.helidon.http.Http$HeaderParam", true),
                new ChangeType("jakarta.inject.Inject",    "io.helidon.service.registry.Service$Inject",    true),
                new ChangeType("jakarta.inject.Singleton", "io.helidon.service.registry.Service$Singleton", true)
        );
        Recipe dwParams = new DropwizardParamRecipe();
        Recipe responseType = new ChangeType("javax.ws.rs.core.Response",
                "io.helidon.webserver.http.ServerResponse", true);
        Recipe responseTypeJakarta = new ChangeType("jakarta.ws.rs.core.Response",
                "io.helidon.webserver.http.ServerResponse", true);
        Recipe removeUnused = new RemoveUnusedImports();

        int changed = 0;
        var changedPaths = new ArrayList<String>();

        for (var file : javaFiles) {
            try {
                String originalSource = Files.readString(file);
                var parsed = parser.parse(ctx, originalSource).toList();
                if (parsed.isEmpty()) {
                    continue;
                }

                var updated = applyVisitor(parsed, annotations.getVisitor(), ctx);
                for (var recipe : annotationTypeChanges) {
                    updated = applyVisitor(updated, recipe.getVisitor(), ctx);
                }
                updated = applyVisitor(updated, dwParams.getVisitor(), ctx);
                updated = applyVisitor(updated, responseType.getVisitor(), ctx);
                updated = applyVisitor(updated, responseTypeJakarta.getVisitor(), ctx);
                updated = applyVisitor(updated, removeUnused.getVisitor(), ctx);

                String newSource = updated.get(0).printAll();
                newSource = replaceProducesConsumesWithTodo(newSource);
                newSource = replaceDropwizardAnnotationsWithTodo(newSource);
                newSource = replaceDropwizardViews(newSource);
                newSource = replaceContextAnnotations(newSource);
                newSource = addMediaTypeTodo(newSource);
                newSource = addServerResponseTodo(newSource);
                if (!newSource.equals(originalSource)) {
                    Files.writeString(file, newSource);
                    changed++;
                    changedPaths.add(projectDir.relativize(file).toString());
                }
            } catch (Exception e) {
                System.err.println("[jaxrs-migrator] Failed to transform " + file + ": " + e.getMessage());
            }
        }

        // Deterministic DW orphan handling (DW_POJO, DW_COMMENT, DW_REMOVE)
        var orphanResult = new DwOrphansHandler().handle(projectDir);
        if (orphanResult.filesModified() > 0) {
            System.out.println("  [orphan] DwOrphansHandler modified "
                    + orphanResult.filesModified() + " file(s).");
        }

        return new ResultSummary(javaFiles.size(), changed, changedPaths);
    }

    private List<Path> listJavaFiles(Path projectDir) {
        var files = new ArrayList<Path>();
        try {
            Files.walk(projectDir)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(files::add);
        } catch (IOException e) {
            System.err.println("[jaxrs-migrator] Failed to list Java files: " + e.getMessage());
        }
        return files;
    }

    private List<SourceFile> applyVisitor(List<SourceFile> before,
                                          TreeVisitor<?, ExecutionContext> visitor,
                                          ExecutionContext ctx) {
        var after = new ArrayList<SourceFile>(before.size());
        for (var sourceFile : before) {
            var visited = visitor.visit(sourceFile, ctx);
            if (visited instanceof SourceFile sf) {
                after.add(sf);
            } else if (sourceFile != null) {
                after.add(sourceFile);
            }
        }
        return after;
    }

    private String replaceDropwizardAnnotationsWithTodo(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        var patterns = List.of("Timed", "Metered", "ExceptionMetered", "Counted", "Gauge", "CacheControl");
        var result = source;
        for (var name : patterns) {
            result = result.replaceAll(
                    "(?m)^([ \\t]*)@" + name + "\\s*\\([^)]*\\)\\s*$",
                    "$1// DW_MIGRATION_TODO: Replace Dropwizard @" + name +
                            " with Helidon declarative metrics/caching when available.");
            result = result.replaceAll(
                    "(?m)^([ \\t]*)@" + name + "\\s*$",
                    "$1// DW_MIGRATION_TODO: Replace Dropwizard @" + name +
                            " with Helidon declarative metrics/caching when available.");
        }
        result = result.replaceAll("(?m)^import\\s+com\\.codahale\\.metrics\\.annotation\\..*;\\s*$\\R?", "");
        result = result.replaceAll("(?m)^import\\s+io\\.dropwizard\\.jersey\\.caching\\.CacheControl;\\s*$\\R?", "");
        return result;
    }

    private String replaceContextAnnotations(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        var result = source;
        result = result.replaceAll("(?m)^import\\s+javax\\.ws\\.rs\\.core\\.Context;\\s*$\\R?", "");
        result = result.replaceAll("(?m)^import\\s+jakarta\\.ws\\.rs\\.core\\.Context;\\s*$\\R?", "");
        result = result.replaceAll(
                "(?m)^([ \\t]*)@Context\\s*$",
                "$1// DW_MIGRATION_TODO: Replace Context injection with Helidon declarative injection.");
        result = result.replaceAll(
                "(?m)@Context\\b",
                "/* DW_MIGRATION_TODO: Replace Context injection with Helidon declarative injection. */");
        return result;
    }

    String replaceDropwizardViews(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        boolean hasViewImport = source.contains("io.dropwizard.views.View");
        boolean hasViewUsage  = source.contains(" View ") &&
                (source.contains("public ") || source.contains("return new View("));
        if (!hasViewImport && !hasViewUsage) {
            return source;
        }
        var result = source;
        result = result.replaceAll("(?m)^import\\s+io\\.dropwizard\\.views\\.View;\\s*$\\R?", "");
        result = result.replaceAll("(?m)^import\\s+io\\.dropwizard\\.views\\.common\\.View;\\s*$\\R?", "");
        result = result.replaceAll("(?m)^(\\s*public\\s+class\\s+\\w+)\\s+extends\\s+View\\s*\\{",
                "$1 {");
        result = result.replaceAll("(?m)^([ \\t]*)super\\([^;]*\\);\\s*$",
                "$1// DW_MIGRATION_TODO: Dropwizard View constructor removed; migrate to Helidon templating.");
        result = result.replaceAll(
                "(?m)^([ \\t]*(?:(?:public|protected|private)\\s+)?(?:static\\s+)?(?:final\\s+)?)View(\\s+\\w+\\s*\\()",
                "$1String /* DW_MIGRATION_TODO[pojo]: Was View return type - implement template rendering manually */$2");
        result = result.replaceAll(
                "(?s)return\\s+new\\s+View\\s*\\([^)]*\\)\\s*\\{[^}]*\\}\\s*;",
                "return \"\"; // DW_MIGRATION_TODO[manual]: Was returning Dropwizard View - implement template rendering manually");
        result = result.replaceAll(
                "(?m)return\\s+new\\s+View\\s*\\([^;)]*\\);",
                "return \"\"; // DW_MIGRATION_TODO[manual]: Was returning Dropwizard View - implement template rendering manually");
        if (!result.contains("DW_MIGRATION_TODO: Dropwizard View")) {
            var todo = "// DW_MIGRATION_TODO: Dropwizard Views are not supported in Helidon SE Declarative; migrate to a templating solution.\n";
            if (result.startsWith("package ")) {
                result = result.replaceFirst("(?m)^package\\s+[^;]+;\\s*\\R",
                        "$0\n" + todo);
            } else {
                result = todo + result;
            }
        }
        return result;
    }

    private String addMediaTypeTodo(String source) {
        if (source == null || source.isBlank() || !source.contains("MediaType.")) {
            return source;
        }
        if (source.contains("DW_MIGRATION_TODO: Review media type")) {
            return source;
        }
        var todo = "// DW_MIGRATION_TODO: Review @Produces/@Consumes media types for Helidon declarative.\n";
        if (source.startsWith("package ")) {
            return source.replaceFirst("(?m)^package\\s+[^;]+;\\s*\\R", "$0\n" + todo);
        }
        return todo + source;
    }

    private String replaceProducesConsumesWithTodo(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        var result = source;
        result = result.replaceAll("(?m)^import\\s+javax\\.ws\\.rs\\.Produces;\\s*$\\R?", "");
        result = result.replaceAll("(?m)^import\\s+jakarta\\.ws\\.rs\\.Produces;\\s*$\\R?", "");
        result = result.replaceAll("(?m)^import\\s+javax\\.ws\\.rs\\.Consumes;\\s*$\\R?", "");
        result = result.replaceAll("(?m)^import\\s+jakarta\\.ws\\.rs\\.Consumes;\\s*$\\R?", "");
        result = replaceAnnotationWithTodo(result, "Produces");
        result = replaceAnnotationWithTodo(result, "Consumes");
        return result;
    }

    private String replaceAnnotationWithTodo(String source, String annotationName) {
        Pattern pattern = Pattern.compile("(?m)^([ \\t]*)@" + annotationName + "\\s*(\\([^)]*\\))?\\s*$");
        Matcher matcher = pattern.matcher(source);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String indent = matcher.group(1);
            String args = matcher.group(2);
            if (args == null) {
                args = "";
            }
            String replacement = indent + "// DW_MIGRATION_TODO: Review @" + annotationName
                    + args + " for Helidon declarative.";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String addServerResponseTodo(String source) {
        if (source == null || source.isBlank() || !source.contains("ServerResponse")) {
            return source;
        }
        if (source.contains("DW_MIGRATION_TODO: Review ServerResponse")) {
            return source;
        }
        var todo = "// DW_MIGRATION_TODO: Review ServerResponse usage and adjust to Helidon declarative patterns.\n";
        if (source.startsWith("package ")) {
            return source.replaceFirst("(?m)^package\\s+[^;]+;\\s*\\R", "$0\n" + todo);
        }
        return todo + source;
    }
}
