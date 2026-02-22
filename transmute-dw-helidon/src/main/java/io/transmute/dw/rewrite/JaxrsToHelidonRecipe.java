package io.transmute.dw.rewrite;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.internal.ListUtils;

import java.util.Comparator;
import java.util.List;

/**
 * OpenRewrite recipe that migrates common JAX-RS annotations and DI annotations
 * to Helidon SE Declarative equivalents, and adjusts imports.
 */
public class JaxrsToHelidonRecipe extends Recipe {

    /**
     * Type stubs for JAX-RS (source) and Helidon (target) annotation types.
     *
     * <p>Used in two places:
     * <ul>
     *   <li>The recipe's {@code JavaTemplate} builders -- so that replacement
     *       templates such as {@code @Http.GET} can resolve the nested
     *       {@code Http.GET} type.</li>
     *   <li>{@link JaxrsAnnotationsMigrator}'s main {@code JavaParser} -- so
     *       that JAX-RS annotations on the input are fully attributed (given
     *       their FQN types) during initial parsing, which is required for
     *       both the FQN guard and the {@code replace()} coordinates to work.</li>
     * </ul>
     */
    static final String[] JAXRS_AND_HELIDON_STUBS = {
        // javax.ws.rs -- source annotation stubs
        "package javax.ws.rs; public @interface GET {}",
        "package javax.ws.rs; public @interface POST {}",
        "package javax.ws.rs; public @interface PUT {}",
        "package javax.ws.rs; public @interface DELETE {}",
        "package javax.ws.rs; public @interface PATCH {}",
        "package javax.ws.rs; public @interface HEAD {}",
        "package javax.ws.rs; public @interface OPTIONS {}",
        "package javax.ws.rs; public @interface Path { String value() default \"\"; }",
        "package javax.ws.rs; public @interface PathParam { String value(); }",
        "package javax.ws.rs; public @interface QueryParam { String value(); }",
        "package javax.ws.rs; public @interface HeaderParam { String value(); }",
        "package javax.ws.rs; public @interface FormParam { String value(); }",
        "package javax.ws.rs; public @interface Produces { String[] value() default {}; }",
        "package javax.ws.rs; public @interface Consumes { String[] value() default {}; }",
        // jakarta.ws.rs -- same stubs with jakarta prefix
        "package jakarta.ws.rs; public @interface GET {}",
        "package jakarta.ws.rs; public @interface POST {}",
        "package jakarta.ws.rs; public @interface PUT {}",
        "package jakarta.ws.rs; public @interface DELETE {}",
        "package jakarta.ws.rs; public @interface PATCH {}",
        "package jakarta.ws.rs; public @interface HEAD {}",
        "package jakarta.ws.rs; public @interface OPTIONS {}",
        "package jakarta.ws.rs; public @interface Path { String value() default \"\"; }",
        "package jakarta.ws.rs; public @interface PathParam { String value(); }",
        "package jakarta.ws.rs; public @interface QueryParam { String value(); }",
        "package jakarta.ws.rs; public @interface HeaderParam { String value(); }",
        "package jakarta.ws.rs; public @interface FormParam { String value(); }",
        "package jakarta.ws.rs; public @interface Produces { String[] value() default {}; }",
        "package jakarta.ws.rs; public @interface Consumes { String[] value() default {}; }",
        // javax.inject / jakarta.inject
        "package javax.inject; public @interface Inject {}",
        "package javax.inject; public @interface Singleton {}",
        "package jakarta.inject; public @interface Inject {}",
        "package jakarta.inject; public @interface Singleton {}",
        // Helidon targets -- nested annotation types used by JavaTemplate replacement text
        "package io.helidon.http; public class Http { public @interface GET {} public @interface POST {} public @interface PUT {} public @interface DELETE {} public @interface PATCH {} public @interface HEAD {} public @interface OPTIONS {} public @interface Path { String value() default \"\"; } public @interface PathParam { String value(); } public @interface QueryParam { String value(); } public @interface HeaderParam { String value(); } public @interface Entity {} }",
        "package io.helidon.service.registry; public class Service { public @interface Inject {} public @interface Singleton {} }",
        "package io.helidon.webserver.http; public class RestServer { public @interface Endpoint {} }"
    };

    @Override
    public String getDisplayName() {
        return "JAX-RS -> Helidon annotations";
    }

    @Override
    public String getDescription() {
        return "Rewrites JAX-RS annotations to Helidon SE Declarative annotations " +
               "and ensures required imports.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                var cd = super.visitClassDeclaration(classDecl, ctx);

                if (hasAnyPath(cd)) {
                    cd = addClassAnnotationIfMissing(cd, "@RestServer.Endpoint",
                            "io.helidon.webserver.http.RestServer");
                    cd = addClassAnnotationIfMissing(cd, "@Service.Singleton",
                            "io.helidon.service.registry.Service");
                }
                return cd;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                var md = super.visitMethodDeclaration(method, ctx);
                if (!hasHttpBodyMethod(md.getLeadingAnnotations())) {
                    return md;
                }
                if (md.getParameters() == null || md.getParameters().isEmpty()) {
                    return md;
                }
                var updatedParams = ListUtils.map(md.getParameters(), param -> {
                    if (!(param instanceof J.VariableDeclarations vd)) {
                        return param;
                    }
                    if (shouldAddEntityAnnotation(vd)) {
                        maybeAddImport("io.helidon.http.Http");
                        return addAnnotation(vd, "@Http.Entity", "io.helidon.http.Http");
                    }
                    return param;
                });
                return md.withParameters(updatedParams);
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                var a = annotation;
                var name = a.getSimpleName();

                // FQN guard: skip annotations whose fully-qualified type is already a
                // Helidon type (e.g. @Http.GET on a second run). Only transform types
                // from javax.ws.rs, jakarta.ws.rs, javax.inject, or jakarta.inject.
                // Unresolved types (null or not FullyQualified) fall through unchanged.
                if (!isJaxrsOrInjectType(a)) {
                    return a;
                }

                if (isProducesOrConsumes(name)) {
                    maybeRemoveImport("javax.ws.rs." + name);
                    maybeRemoveImport("jakarta.ws.rs." + name);
                    return a;
                }

                return a;
            }

            private JavaTemplate tpl(String code, String importFqn) {
                return JavaTemplate.builder(code)
                        .contextSensitive()
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(JAXRS_AND_HELIDON_STUBS))
                        .imports(importFqn)
                        .build();
            }

            private J.VariableDeclarations addAnnotation(J.VariableDeclarations vd,
                                                         String annotation,
                                                         String importType) {
                Cursor cursor = new Cursor(getCursor(), vd);
                J applied = tpl(annotation, importType)
                        .apply(cursor, vd.getCoordinates()
                                .addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
                return (J.VariableDeclarations) applied;
            }

            private boolean isJaxrsOrInjectType(J.Annotation a) {
                if (!(a.getType() instanceof JavaType.FullyQualified fq)) {
                    return true;
                }
                var fqn = fq.getFullyQualifiedName();
                return fqn.startsWith("javax.ws.rs.")
                        || fqn.startsWith("jakarta.ws.rs.")
                        || fqn.startsWith("javax.inject.")
                        || fqn.startsWith("jakarta.inject.");
            }

            private boolean hasAnyPath(J.ClassDeclaration classDecl) {
                if (hasPathAnnotation(classDecl.getLeadingAnnotations())) {
                    return true;
                }
                if (classDecl.getBody() == null) {
                    return false;
                }
                for (var stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration md) {
                        if (hasPathAnnotation(md.getLeadingAnnotations())) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasPathAnnotation(List<J.Annotation> annotations) {
                for (var ann : annotations) {
                    var name = ann.getSimpleName();
                    if ("Path".equals(name)) {
                        return true;
                    }
                    if (ann.getType() instanceof JavaType.FullyQualified fq) {
                        var fqn = fq.getFullyQualifiedName();
                        if (fqn.endsWith(".Path") || fqn.endsWith("$Path")) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasAnnotation(List<J.Annotation> annotations, String simpleName) {
                for (var ann : annotations) {
                    if (simpleName.equals(ann.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private J.ClassDeclaration addClassAnnotationIfMissing(J.ClassDeclaration classDecl,
                                                                   String annotation,
                                                                   String importType) {
                String template = annotation;
                if ("@RestServer.Endpoint".equals(annotation)) {
                    template = "@io.helidon.webserver.http.RestServer.Endpoint";
                }
                var simpleName = annotation.startsWith("@") ? annotation.substring(1) : annotation;
                int dot = simpleName.lastIndexOf('.');
                if (dot >= 0) {
                    simpleName = simpleName.substring(dot + 1);
                }
                if (hasAnnotation(classDecl.getLeadingAnnotations(), simpleName)) {
                    return classDecl;
                }
                maybeAddImport(importType);
                Cursor cursor = new Cursor(getCursor().getParent(), classDecl);
                return tpl(template, importType)
                        .apply(cursor,
                                classDecl.getCoordinates()
                                        .addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
            }

            private boolean isProducesOrConsumes(String name) {
                return "Produces".equals(name) || "Consumes".equals(name);
            }

            private boolean hasHttpBodyMethod(List<J.Annotation> annotations) {
                for (var ann : annotations) {
                    String name = ann.getSimpleName();
                    if ("POST".equals(name) || "PUT".equals(name) || "PATCH".equals(name) || "DELETE".equals(name)) {
                        return true;
                    }
                    if (ann.getType() instanceof JavaType.FullyQualified fq) {
                        var fqn = fq.getFullyQualifiedName();
                        if (fqn.endsWith("$POST") || fqn.endsWith("$PUT")
                                || fqn.endsWith("$PATCH") || fqn.endsWith("$DELETE")) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean shouldAddEntityAnnotation(J.VariableDeclarations vd) {
                var annotations = vd.getLeadingAnnotations();
                if (annotations == null || annotations.isEmpty()) {
                    return true;
                }
                for (var ann : annotations) {
                    var name = ann.getSimpleName();
                    if ("PathParam".equals(name) || "QueryParam".equals(name)
                            || "HeaderParam".equals(name) || "Entity".equals(name)) {
                        return false;
                    }
                    if (ann.getType() instanceof JavaType.FullyQualified fq) {
                        var fqn = fq.getFullyQualifiedName();
                        if (fqn.endsWith("$PathParam") || fqn.endsWith("$QueryParam")
                                || fqn.endsWith("$HeaderParam") || fqn.endsWith("$Entity")) {
                            return false;
                        }
                    }
                }
                return true;
            }
        };
    }
}
