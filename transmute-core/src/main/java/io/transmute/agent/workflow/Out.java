package io.transmute.agent.workflow;

import io.transmute.tool.Ansi;

/**
 * ANSI-colored console helpers shared across the workflow package.
 */
final class Out {
    static final String RESET  = Ansi.RESET;
    static final String BOLD   = Ansi.BOLD;
    static final String DIM    = Ansi.DIM;
    static final String CYAN   = Ansi.CYAN;
    static final String GREEN  = Ansi.GREEN;
    static final String RED    = Ansi.RED;
    static final String YELLOW = Ansi.YELLOW;

    private static final String RULE_STR = DIM + "─".repeat(80) + RESET;

    static void step(int n, int total, String title) {
        System.out.println();
        System.out.println(BOLD + CYAN + "[" + n + "/" + total + "]" + RESET
                + BOLD + "  " + title + RESET);
    }

    static void rule() {
        System.out.println(RULE_STR);
    }

    /**
     * Prints a header line, an underline of {@code title.length() + 1} dashes, and a blank line.
     * Use {@code title} for the plain visible text; {@code subtitle} (dimmed) appended after two spaces.
     */
    static void sectionHeader(String title, String subtitle) {
        System.out.println("  " + BOLD + title + RESET
                + (subtitle.isEmpty() ? "" : "  " + DIM + subtitle + RESET));
        System.out.println("  " + DIM + "─".repeat(title.length() + 1) + RESET);
        System.out.println();
    }

    static void sectionHeader(String title) {
        sectionHeader(title, "");
    }

    static void ok(String msg) {
        System.out.println("  " + GREEN + "✓" + RESET + "  " + msg);
    }

    static void info(String msg) {
        System.out.println("  " + msg);
    }

    static void warn(String msg) {
        System.out.println("  " + YELLOW + "⚠" + RESET + "  " + msg);
    }

    static void error(String msg) {
        System.err.println("  " + RED + "✗" + RESET + "  " + msg);
    }

    static void migrationProject(String name) {
        System.out.println("  " + CYAN + ">> " + RESET + BOLD + name + RESET + DIM + "  (project)" + RESET);
    }

    static void migrationFile(String fileName, java.util.List<String> migrationNames) {
        var labels = migrationNames.stream().map(n -> "[" + n + "]")
                .collect(java.util.stream.Collectors.joining(" "));
        System.out.println("  " + CYAN + ">> " + RESET + BOLD + fileName + RESET
                + "  " + DIM + labels + RESET);
    }

    static void dryRunSkip() {
        System.out.println("    " + DIM + "[dry-run] skipping agent invocation" + RESET);
    }

    static String bold(String text) {
        return BOLD + text + RESET;
    }

    private Out() {}
}
