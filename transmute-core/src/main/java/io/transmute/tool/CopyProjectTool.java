package io.transmute.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Copies a source project directory to an output directory for migration.
 * The original project is never modified.
 */
public class CopyProjectTool {

    public record CopyResult(boolean success, String message) {}

    @Tool("Copy the source project to an output directory for migration. " +
          "The original project files are never modified. " +
          "Skips target/, .git/, and .idea/ directories.")
    public String copyProject(
            @P("Absolute path to the source project directory") String sourceDir,
            @P("Absolute path to the output directory") String outputDir) {
        return copyProjectResult(sourceDir, outputDir).message();
    }

    public CopyResult copyProjectResult(String sourceDir, String outputDir) {
        try {
            var source = Path.of(sourceDir);
            var target = Path.of(outputDir);

            if (Files.exists(target)) {
                return new CopyResult(false,
                        "Output directory already exists: " + outputDir
                                + ". Delete it first or choose a different output directory.");
            }

            ToolLog.log("copy_project " + sourceDir + " -> " + outputDir);
            copyDirectory(source, target);
            return new CopyResult(true, "Project copied to: " + outputDir);
        } catch (IOException e) {
            return new CopyResult(false, "Error copying project: " + e.getMessage());
        }
    }

    public static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                var relative = source.relativize(dir).toString();
                if (relative.equals("target") || relative.startsWith("target/")
                        || relative.equals("target\\") || relative.startsWith("target\\")
                        || relative.equals(".git") || relative.startsWith(".git/")
                        || relative.equals(".idea") || relative.startsWith(".idea/")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
