package io.transmute.compile;

import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses {@code mvn compile -q} stderr output into structured {@link CompileError} records.
 *
 * <p>Three-step FQN enrichment:
 * <ol>
 *   <li>{@code symbolMap} in {@link JavaFileInfo} (from OR type attribution — primary)</li>
 *   <li>Explicit {@code import} statements in the file</li>
 *   <li>{@link io.transmute.catalog.SourceTypeRegistry} reverse index</li>
 * </ol>
 */
public class CompileErrorParser {

    // Matches: "[ERROR] /path/To/File.java:[42,10] error message"
    // or:      "[ERROR] /path/To/File.java:42: error: error message"
    private static final Pattern ERROR_LINE = Pattern.compile(
            "(?:\\[ERROR\\]\\s+)?(.+?\\.java):\\[(\\d+)(?:,\\d+)?\\]\\s*(.+)"
    );
    private static final Pattern ERROR_LINE_COLON = Pattern.compile(
            "(?:\\[ERROR\\]\\s+)?(.+?\\.java):(\\d+):\\s*error:\\s*(.+)"
    );
    // Extracts symbol from "cannot find symbol ... symbol: class Foo" or "symbol: variable bar"
    private static final Pattern SYMBOL = Pattern.compile(
            "cannot find symbol.*?symbol\\s*:\\s*(?:class|interface|variable|method)\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final ProjectInventory inventory;

    public CompileErrorParser(ProjectInventory inventory) {
        this.inventory = inventory;
    }

    /**
     * Parses raw {@code mvn compile} output and returns structured errors.
     */
    public List<CompileError> parse(String compilerOutput) {
        var errors = new ArrayList<CompileError>();
        if (compilerOutput == null || compilerOutput.isBlank()) {
            return errors;
        }

        var lines = compilerOutput.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            var match = ERROR_LINE.matcher(line);
            if (!match.find()) {
                match = ERROR_LINE_COLON.matcher(line);
                if (!match.find()) {
                    continue;
                }
            }

            var file = match.group(1);
            var lineNum = Integer.parseInt(match.group(2));
            var message = match.group(3).trim();

            // Collect continuation lines (symbol:, location:)
            var full = new StringBuilder(message);
            while (i + 1 < lines.length && (lines[i + 1].trim().startsWith("symbol")
                    || lines[i + 1].trim().startsWith("location"))) {
                full.append(" ").append(lines[++i].trim());
            }
            var fullMessage = full.toString();

            var rawSymbol = extractSymbol(fullMessage);
            var resolvedFqn = rawSymbol.flatMap(sym -> resolveFqn(file, sym));

            errors.add(new CompileError(file, lineNum, fullMessage, rawSymbol, resolvedFqn));
        }

        return errors;
    }

    // ── FQN enrichment ────────────────────────────────────────────────────────

    private Optional<String> extractSymbol(String message) {
        var matcher = SYMBOL.matcher(message);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<String> resolveFqn(String file, String simpleName) {
        // Step 1: symbolMap from OR type attribution
        var fileInfo = inventory.fileByPath(file);
        if (fileInfo != null) {
            var fqn = fileInfo.symbolMap().get(simpleName);
            if (fqn != null) {
                return Optional.of(fqn);
            }

            // Step 2: import statements
            var fromImport = fileInfo.imports().stream()
                    .filter(i -> i.endsWith("." + simpleName))
                    .findFirst();
            if (fromImport.isPresent()) {
                return fromImport;
            }
        }

        return Optional.empty();
    }
}
