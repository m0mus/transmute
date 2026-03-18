package io.transmute.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File I/O operations for reading and writing source files during migration.
 * All write operations target the configured output directory only.
 */
public class FileOperationsTool {

    private final String outputDir;

    public FileOperationsTool(String outputDir) {
        this.outputDir = outputDir;
    }

    @Tool("Read the contents of a source file within the output directory.")
    public String readFile(
            @P("Path to the file (relative to outputDir)") String filePath) {
        try {
            if (outputDir == null || outputDir.isBlank()) {
                return "Error reading file: outputDir is not configured";
            }
            var outRoot = Path.of(outputDir).toAbsolutePath().normalize();
            var path = Path.of(filePath);
            if (path.isAbsolute()) {
                return "Error reading file: absolute paths are not allowed; use outputDir-relative paths";
            }
            path = outRoot.resolve(path).normalize();
            if (!isUnderDirectory(outRoot, path)) {
                return "Error reading file: path is outside configured outputDir: " + filePath;
            }
            var content = Files.readString(path);
            ToolLog.log("read_file " + filePath + " (" + content.length() + " chars)");
            return content;
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool("Write transformed content to a file in the output directory. " +
          "Creates parent directories if needed.")
    public String writeFile(
            @P("Path to the output file (relative to outputDir)") String filePath,
            @P("The content to write") String content) {
        try {
            if (outputDir == null || outputDir.isBlank()) {
                return "Error writing file: outputDir is not configured";
            }
            var outRoot = Path.of(outputDir).toAbsolutePath().normalize();
            var path = Path.of(filePath);
            if (path.isAbsolute()) {
                return "Error writing file: absolute paths are not allowed; use outputDir-relative paths";
            }
            path = outRoot.resolve(path).normalize();
            if (!isUnderDirectory(outRoot, path)) {
                return "Error writing file: path is outside configured outputDir: " + filePath;
            }
            Files.createDirectories(path.getParent());
            String before = null;
            try {
                if (Files.exists(path)) {
                    before = Files.readString(path);
                }
            } catch (IOException ignored) {}
            Files.writeString(path, content);
            ToolLog.log("write_file " + filePath + " (" + content.length() + " chars)");
            if (ToolLog.enabled()) {
                System.out.println(formatDiffBlock(filePath, summarizeDiff(before, content), true));
            }
            return "Successfully wrote " + content.length() + " chars to " + filePath;
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool("List all .java files in a project directory under outputDir, recursively.")
    public String listJavaFiles(
            @P("Path to the project directory (relative to outputDir)") String projectDir) {
        try {
            if (outputDir == null || outputDir.isBlank()) {
                return "Error listing files: outputDir is not configured";
            }
            var outRoot = Path.of(outputDir).toAbsolutePath().normalize();
            var root = Path.of(projectDir);
            if (root.isAbsolute()) {
                return "Error listing files: absolute paths are not allowed; use outputDir-relative paths";
            }
            root = outRoot.resolve(root).normalize();
            if (!isUnderDirectory(outRoot, root)) {
                return "Error listing files: path is outside configured outputDir: " + projectDir;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                var files = walk.filter(p -> p.toString().endsWith(".java"))
                        .map(Path::toString)
                        .sorted()
                        .collect(Collectors.joining("\n"));
                ToolLog.log("list_java_files " + projectDir
                        + " (" + (files.isEmpty() ? 0 : files.split("\n").length) + " files)");
                return files.isEmpty() ? "No .java files found" : files;
            }
        } catch (IOException e) {
            return "Error listing files: " + e.getMessage();
        }
    }

    private boolean isUnderDirectory(Path baseDir, Path candidate) {
        var normalized = candidate.toAbsolutePath().normalize();
        return normalized.startsWith(baseDir);
    }

    private String summarizeDiff(String before, String after) {
        if (before == null) {
            return "new file lines=" + lineCount(after);
        }
        if (before.equals(after)) {
            return "no change";
        }
        var a = before.split("\\R", -1);
        var b = after.split("\\R", -1);

        // Build a unified-diff-style output by scanning for changed hunks.
        // Use a simple O(n) scan: mark each line as changed or unchanged,
        // then group adjacent changes into hunks with 2-line context.
        int n = Math.max(a.length, b.length);
        // LCS-based matching is expensive; use line-equality scan on the shorter dimension.
        // Produce hunks: collect pairs (aLine, bLine) that differ.
        var sb = new StringBuilder();
        sb.append("lines ").append(a.length).append("->").append(b.length).append("\n");

        // Walk both arrays simultaneously, emitting hunks of differing lines.
        int ai = 0, bi = 0;
        int CONTEXT = 2;
        int lastHunkEnd = -1;
        while (ai < a.length || bi < b.length) {
            if (ai < a.length && bi < b.length && a[ai].equals(b[bi])) {
                ai++; bi++;
                continue;
            }
            // Found a difference — collect the hunk
            int hunkAStart = ai, hunkBStart = bi;
            // Advance until lines agree again (up to 8 lines lookahead)
            int lookahead = 8;
            while ((ai < a.length || bi < b.length) && lookahead-- > 0) {
                if (ai < a.length && bi < b.length && a[ai].equals(b[bi])) break;
                if (ai < a.length) ai++;
                if (bi < b.length) bi++;
            }
            // Print context before hunk
            if (sb.length() > 50 && hunkAStart > lastHunkEnd + 1) {
                sb.append("  ...\n");
            }
            for (int i = Math.max(0, hunkAStart - CONTEXT); i < hunkAStart; i++) {
                sb.append("   ").append(a[i]).append("\n");
            }
            for (int i = hunkAStart; i < ai && i < a.length; i++) {
                sb.append("  -").append(a[i]).append("\n");
            }
            for (int i = hunkBStart; i < bi && i < b.length; i++) {
                sb.append("  +").append(b[i]).append("\n");
            }
            lastHunkEnd = ai;
        }
        var summary = sb.toString().replace("\r", "");
        return summary.length() > 2000 ? summary.substring(0, 2000) + "\n  ..." : summary;
    }

    private int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\\R", -1).length;
    }

    private String formatDiffBlock(String filePath, String diffSummary, boolean ansi) {
        final String reset = ansi ? Ansi.RESET : "";
        final String dim   = ansi ? Ansi.DIM   : "";
        final String red   = ansi ? Ansi.RED   : "";
        final String green = ansi ? Ansi.GREEN : "";
        final String cyan  = ansi ? Ansi.CYAN  : "";

        var sb = new StringBuilder();
        sb.append(cyan).append("[diff] ").append(filePath).append(reset).append("\n");
        if (diffSummary == null || diffSummary.isBlank() || diffSummary.equals("no change")) {
            sb.append("  ").append(dim).append(diffSummary == null ? "no details" : diffSummary).append(reset).append("\n");
            return sb.toString();
        }
        if (diffSummary.startsWith("new file")) {
            sb.append("  ").append(dim).append(diffSummary).append(reset).append("\n");
            return sb.toString();
        }
        for (var line : diffSummary.split("\\n")) {
            if (line.isBlank()) continue;
            String stripped = line.stripLeading();
            char lead = stripped.isEmpty() ? ' ' : stripped.charAt(0);
            String color = switch (lead) {
                case '-' -> red;
                case '+' -> green;
                default  -> dim;
            };
            sb.append(color).append(line).append(reset).append("\n");
        }
        return sb.toString();
    }
}
