package io.transmute.inventory;

import java.util.Map;
import java.util.Set;

/**
 * Immutable record of a scanned Java source file's structural metadata.
 * Collected by {@link JavaProjectVisitor} via OpenRewrite type attribution.
 */
public record JavaFileInfo(
        String sourceFile,
        String className,
        Set<String> annotationTypes,
        Set<String> imports,
        Set<String> superTypes,
        Map<String, String> symbolMap   // simpleName -> FQN from OR type resolution
) {
    public JavaFileInfo {
        annotationTypes = Set.copyOf(annotationTypes);
        imports = Set.copyOf(imports);
        superTypes = Set.copyOf(superTypes);
        symbolMap = Map.copyOf(symbolMap);
    }
}
