package io.transmute.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Package-private helper that builds and executes a Maven command and returns the output.
 */
final class MavenRunner {

    private MavenRunner() {}

    /**
     * Runs Maven with the given goals in the specified working directory.
     *
     * @param workingDir the directory in which to invoke Maven
     * @param goals      Maven goals/flags (e.g. {@code ["compile", "-q"]})
     * @param offline    if {@code true}, passes {@code -o} to Maven
     * @return the combined stdout+stderr output of the process
     * @throws Exception if the process cannot be started or interrupted
     */
    static String run(Path workingDir, List<String> goals, boolean offline) throws Exception {
        var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        var cmd = buildCommand(isWindows, goals, offline);

        var pb = new ProcessBuilder(cmd)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);

        var process = pb.start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        process.waitFor();
        return output;
    }

    /**
     * Returns the exit code of the Maven process along with its output.
     */
    static RunResult runWithExitCode(Path workingDir, List<String> goals, boolean offline) throws Exception {
        var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        var cmd = buildCommand(isWindows, goals, offline);

        var pb = new ProcessBuilder(cmd)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);

        var process = pb.start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        int exitCode = process.waitFor();
        return new RunResult(exitCode, output);
    }

    record RunResult(int exitCode, String output) {
        boolean success() { return exitCode == 0; }
    }

    private static List<String> buildCommand(boolean isWindows, List<String> goals, boolean offline) {
        var cmd = new ArrayList<String>();
        if (isWindows) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add("mvn");
        } else {
            cmd.add("mvn");
        }
        cmd.addAll(goals);
        if (offline) {
            cmd.add("-o");
        }
        return cmd;
    }
}
