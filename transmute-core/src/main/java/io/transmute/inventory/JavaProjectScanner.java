package io.transmute.inventory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scans a Java project directory and produces a {@link ProjectInventory}.
 *
 * <p>This is an inventory-layer concern: it drives {@link JavaProjectVisitor}
 * over the source tree using the OpenRewrite Java parser and returns the
 * accumulated result.
 */
public class JavaProjectScanner {

    /**
     * Scans the given source directory and returns its {@link ProjectInventory}.
     *
     * @param sourceDir absolute or relative path to the project root
     * @return populated inventory; never {@code null}
     */
    public ProjectInventory scan(String sourceDir) {
        var visitor = new JavaProjectVisitor();
        var inv = visitor.getInitialValue();
        inv.setRootDir(sourceDir);
        inv.setProject(Path.of(sourceDir).getFileName().toString());
        try {
            var parser = org.openrewrite.java.JavaParser.fromJavaVersion()
                    .logCompilationWarningsAndErrors(false)
                    .build();
            var sourceRoot = Path.of(sourceDir);
            try (var walk = Files.walk(sourceRoot)) {
                var javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
                if (!javaFiles.isEmpty()) {
                    var ctx = new org.openrewrite.InMemoryExecutionContext(e -> inv.addError(e.getMessage()));
                    var sources = parser.parse(javaFiles, sourceRoot, ctx).toList();
                    for (var source : sources) {
                        visitor.visit(source, inv);
                    }
                }
            }
        } catch (Exception e) {
            inv.addWarning("Inventory scan error: " + e.getMessage());
        }
        return inv;
    }
}
