package io.transmute.inventory;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.*;

/**
 * A generic OpenRewrite {@link Recipe} that scans Java source files and
 * accumulates structural metadata into a {@link ProjectInventory}.
 *
 * <p>Uses cursor message-passing (NOT instance fields) so this visitor is
 * safe for concurrent/parallel recipe execution.
 *
 * <p>Usage:
 * <pre>{@code
 *   var visitor = new JavaProjectVisitor();
 *   var inventory = visitor.scan(projectPath);
 * }</pre>
 */
public class JavaProjectVisitor extends JavaIsoVisitor<ProjectInventory> {

    private static final String MSG_FILE_CTX = "transmute.fc";

    /**
     * Scan a project directory and return the accumulated inventory.
     * Callers should invoke OpenRewrite's recipe runner; this method
     * is the visitor side that fills the accumulator.
     */
    public ProjectInventory getInitialValue() {
        return new ProjectInventory();
    }

    @Override
    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ProjectInventory inventory) {
        var fileContext = new FileContext(cu.getSourcePath().toString());
        getCursor().putMessage(MSG_FILE_CTX, fileContext);

        // Collect imports
        for (var imp : cu.getImports()) {
            fileContext.imports.add(imp.getTypeName());
        }

        // Visit the rest of the tree
        var result = super.visitCompilationUnit(cu, inventory);

        // Build JavaFileInfo and add to inventory
        var info = new JavaFileInfo(
                fileContext.sourceFile,
                fileContext.className,
                fileContext.annotationTypes,
                fileContext.imports,
                fileContext.superTypes,
                fileContext.symbolMap
        );
        inventory.getJavaFiles().add(info);

        return result;
    }

    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ProjectInventory inventory) {
        var fc = getCursor().<FileContext>getNearestMessage(MSG_FILE_CTX);
        if (fc != null && fc.className == null) {
            // Primary (outermost) class — resolve FQN
            var type = classDecl.getType();
            if (type instanceof JavaType.Class cls) {
                fc.className = cls.getFullyQualifiedName();
                // Collect extends / implements
                if (cls.getSupertype() != null) {
                    addResolvedSuperType(cls.getSupertype().getFullyQualifiedName(), fc);
                }
                for (var iface : cls.getInterfaces()) {
                    addResolvedSuperType(iface.getFullyQualifiedName(), fc);
                }
            } else if (classDecl.getSimpleName() != null) {
                // Fallback: use simple name from source path
                fc.className = classDecl.getSimpleName();
            }
            // Fallback: resolve extends/implements from AST when type info is incomplete
            if (classDecl.getExtends() != null) {
                resolveTypeRefToSuperType(classDecl.getExtends(), fc);
            }
            if (classDecl.getImplements() != null) {
                for (var impl : classDecl.getImplements()) {
                    resolveTypeRefToSuperType(impl, fc);
                }
            }

            // Collect annotation FQNs
            for (var annotation : classDecl.getLeadingAnnotations()) {
                resolveAnnotationType(annotation, fc);
            }
        }
        return super.visitClassDeclaration(classDecl, inventory);
    }

    @Override
    public J.Annotation visitAnnotation(J.Annotation annotation, ProjectInventory inventory) {
        var fc = getCursor().<FileContext>getNearestMessage(MSG_FILE_CTX);
        if (fc != null) {
            resolveAnnotationType(annotation, fc);
        }
        return super.visitAnnotation(annotation, inventory);
    }

    @Override
    public J.Identifier visitIdentifier(J.Identifier identifier, ProjectInventory inventory) {
        var fc = getCursor().<FileContext>getNearestMessage(MSG_FILE_CTX);
        if (fc != null && identifier.getFieldType() != null) {
            var type = identifier.getFieldType().getType();
            if (type instanceof JavaType.Class cls) {
                fc.symbolMap.put(identifier.getSimpleName(), cls.getFullyQualifiedName());
            }
        }
        return super.visitIdentifier(identifier, inventory);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Adds a superType FQN, resolving unqualified names from imports if needed.
     */
    private void addResolvedSuperType(String fqn, FileContext fc) {
        if (fqn != null && !fqn.isEmpty()) {
            if (fqn.contains(".")) {
                fc.superTypes.add(fqn);
            } else {
                // Unresolved simple name — try to resolve from imports
                var resolved = resolveSimpleNameFromImports(fqn, fc);
                fc.superTypes.add(resolved);
            }
        }
    }

    /**
     * Resolves a type reference (extends/implements clause) to a FQN using imports.
     * Only adds if not already present (avoids duplicating resolved types).
     */
    private void resolveTypeRefToSuperType(org.openrewrite.java.tree.TypeTree typeRef, FileContext fc) {
        String simpleName = null;
        if (typeRef instanceof J.ParameterizedType pt) {
            if (pt.getClazz() instanceof J.Identifier id) {
                simpleName = id.getSimpleName();
            }
        } else if (typeRef instanceof J.Identifier id) {
            simpleName = id.getSimpleName();
        }
        if (simpleName != null) {
            var resolved = resolveSimpleNameFromImports(simpleName, fc);
            fc.superTypes.add(resolved);
        }
    }

    private String resolveSimpleNameFromImports(String simpleName, FileContext fc) {
        return fc.imports.stream()
                .filter(i -> i.endsWith("." + simpleName))
                .findFirst()
                .orElse(simpleName);
    }

    private void resolveAnnotationType(J.Annotation annotation, FileContext fc) {
        var type = annotation.getType();
        if (type instanceof JavaType.Class cls) {
            fc.annotationTypes.add(cls.getFullyQualifiedName());
        } else if (annotation.getAnnotationType() instanceof J.FieldAccess fa) {
            fc.annotationTypes.add(fa.toString());
        } else if (annotation.getAnnotationType() instanceof J.Identifier id) {
            // Resolve via imports
            var simple = id.getSimpleName();
            fc.imports.stream()
                    .filter(i -> i.endsWith("." + simple) || i.endsWith("." + simple + ".*"))
                    .findFirst()
                    .ifPresentOrElse(
                            fc.annotationTypes::add,
                            () -> fc.annotationTypes.add(simple)
                    );
        }
    }

    // ── Per-file accumulator ──────────────────────────────────────────────────

    private static final class FileContext {
        final String sourceFile;
        String className;
        final Set<String> annotationTypes = new LinkedHashSet<>();
        final Set<String> imports = new LinkedHashSet<>();
        final Set<String> superTypes = new LinkedHashSet<>();
        final Map<String, String> symbolMap = new LinkedHashMap<>();

        FileContext(String sourceFile) {
            this.sourceFile = sourceFile;
        }
    }
}
