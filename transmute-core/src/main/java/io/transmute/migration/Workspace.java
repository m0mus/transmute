package io.transmute.migration;

import java.nio.file.Path;

/**
 * Describes the input/output directory layout for a migration run.
 */
public record Workspace(String sourceDir, String outputDir, boolean dryRun) {

    /**
     * Maps an output-directory file path back to the corresponding source path.
     * Returns {@code null} when the file is not under the output directory.
     */
    public String sourceFileFor(String outputFile) {
        var outPath = Path.of(outputDir).toAbsolutePath().normalize();
        var filePath = Path.of(outputFile).toAbsolutePath().normalize();
        if (!filePath.startsWith(outPath)) {
            return null;
        }
        var relative = outPath.relativize(filePath);
        return Path.of(sourceDir).resolve(relative).normalize().toString();
    }

    /**
     * Resolves a source-relative path to an absolute output path.
     *
     * @param sourceRelativePath path relative to sourceDir (e.g. "src/main/java/Foo.java")
     */
    public Path outputPath(String sourceRelativePath) {
        return Path.of(outputDir).resolve(sourceRelativePath).normalize();
    }
}
