package io.transmute.compile;

import java.util.Optional;

/**
 * A single compilation error parsed from {@code mvn compile} output.
 */
public record CompileError(
        String file,
        int line,
        String message,
        Optional<String> rawSymbol,
        Optional<String> resolvedFqn
) {}
