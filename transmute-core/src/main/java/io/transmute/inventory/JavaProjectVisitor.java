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
                    fc.superTypes.add(cls.getSupertype().getFullyQualifiedName());
                }
                for (var iface : cls.getInterfaces()) {
                    fc.superTypes.add(iface.getFullyQualifiedName());
                }
            } else if (classDecl.getSimpleName() != null) {
                // Fallback: use simple name from source path
                fc.className = classDecl.getSimpleName();
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
