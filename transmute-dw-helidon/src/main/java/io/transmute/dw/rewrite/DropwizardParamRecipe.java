package io.transmute.dw.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.J;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converts Dropwizard jersey param types to standard Java types and removes .get() usage.
 */
public class DropwizardParamRecipe extends Recipe {

    private static final Map<String, String> TYPE_MAPPINGS = new HashMap<>();
    private static final Map<String, String> IMPORT_MAPPINGS = new HashMap<>();

    static {
        TYPE_MAPPINGS.put("LocalDateParam", "LocalDate");
        IMPORT_MAPPINGS.put("LocalDateParam", "java.time.LocalDate");

        TYPE_MAPPINGS.put("IntParam", "Integer");
        TYPE_MAPPINGS.put("LongParam", "Long");
        TYPE_MAPPINGS.put("BooleanParam", "Boolean");
        TYPE_MAPPINGS.put("FloatParam", "Float");
        TYPE_MAPPINGS.put("DoubleParam", "Double");
        TYPE_MAPPINGS.put("StringParam", "String");
        TYPE_MAPPINGS.put("UUIDParam", "UUID");
        IMPORT_MAPPINGS.put("UUIDParam", "java.util.UUID");
    }

    @Override
    public String getDisplayName() {
        return "Dropwizard param types -> Java types";
    }

    @Override
    public String getDescription() {
        return "Replaces Dropwizard jersey param types with standard Java types " +
               "and removes .get() accessor usage.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<>() {
            private final ArrayDeque<Set<String>> methodParamNames = new ArrayDeque<>();

            @Override
            public J visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                var names = new HashSet<String>();
                if (method.getParameters() != null) {
                    for (var param : method.getParameters()) {
                        if (param instanceof J.VariableDeclarations vd) {
                            var type = vd.getTypeExpression();
                            if (type instanceof J.Identifier id && TYPE_MAPPINGS.containsKey(id.getSimpleName())) {
                                for (var v : vd.getVariables()) {
                                    names.add(v.getSimpleName());
                                }
                            }
                        }
                    }
                }
                methodParamNames.push(names);
                var result = (J.MethodDeclaration) super.visitMethodDeclaration(method, ctx);
                methodParamNames.pop();
                return result;
            }

            @Override
            public J visitVariableDeclarations(J.VariableDeclarations multi, ExecutionContext ctx) {
                var vd = (J.VariableDeclarations) super.visitVariableDeclarations(multi, ctx);
                var type = vd.getTypeExpression();
                if (type instanceof J.Identifier id && TYPE_MAPPINGS.containsKey(id.getSimpleName())) {
                    var target = TYPE_MAPPINGS.get(id.getSimpleName());
                    maybeRemoveImport("io.dropwizard.jersey.params." + id.getSimpleName());
                    maybeRemoveImport("io.dropwizard.jersey.jsr310." + id.getSimpleName());
                    var importType = IMPORT_MAPPINGS.get(id.getSimpleName());
                    if (importType != null) {
                        maybeAddImport(importType);
                    }
                    var updated = id.withSimpleName(target);
                    vd = vd.withTypeExpression(updated);
                }
                return vd;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                var mi = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!"get".equals(mi.getSimpleName()) || mi.getArguments().size() != 0) {
                    return mi;
                }
                if (methodParamNames.isEmpty()) {
                    return mi;
                }
                var select = mi.getSelect();
                if (select instanceof J.Identifier id) {
                    var names = methodParamNames.peek();
                    if (names.contains(id.getSimpleName())) {
                        return id;
                    }
                }
                return mi;
            }
        };
    }
}
