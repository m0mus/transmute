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

    @Tool("Read the contents of a source file. Can read from both the original project " +
          "(for context) and the output directory (for current state).")
    public String readFile(
            @P("Absolute path to the file to read") String filePath) {
        try {
            var content = Files.readString(Path.of(filePath));
            ToolLog.log("read_file " + filePath + " (" + content.length() + " chars)");
            return content;
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool("Write transformed content to a file in the output directory. " +
          "Creates parent directories if needed.")
    public String writeFile(
            @P("Path to the output file (absolute or relative to outputDir)") String filePath,
            @P("The content to write") String content) {
        try {
            if (outputDir == null || outputDir.isBlank()) {
                return "Error writing file: outputDir is not configured";
            }
            var outRoot = Path.of(outputDir).toAbsolutePath().normalize();
            var path = Path.of(filePath);
            if (!path.isAbsolute()) {
                path = outRoot.resolve(path).normalize();
            }
            if (!isUnderDirectory(outRoot, path)) {
                return "Error writing file: path is outside configured outputDir: " + filePath;
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            ToolLog.log("write_file " + filePath + " (" + content.length() + " chars)");
            return "Successfully wrote " + content.length() + " chars to " + filePath;
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool("List all .java files in a project directory, recursively.")
    public String listJavaFiles(
            @P("Absolute path to the project directory") String projectDir) {
        try (Stream<Path> walk = Files.walk(Path.of(projectDir))) {
            var files = walk.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.joining("\n"));
            ToolLog.log("list_java_files " + projectDir
                    + " (" + (files.isEmpty() ? 0 : files.split("\n").length) + " files)");
            return files.isEmpty() ? "No .java files found" : files;
        } catch (IOException e) {
            return "Error listing files: " + e.getMessage();
        }
    }

    private boolean isUnderDirectory(Path baseDir, Path candidate) {
        var normalized = candidate.toAbsolutePath().normalize();
        return normalized.startsWith(baseDir);
    }
}
