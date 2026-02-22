package io.transmute.tool;

/**
 * Simple logging helper for tool execution.
 */
public final class ToolLog {
    private static final String PREFIX = "[tool] ";
    private static volatile String phase = null;

    private ToolLog() {}

    public static boolean enabled() {
        var sys = System.getProperty("transmute.verbose");
        if (sys != null) {
            return isTruthy(sys);
        }
        var env = System.getenv("TRANSMUTE_VERBOSE");
        return env != null && isTruthy(env);
    }

    public static void log(String message) {
        if (!enabled()) {
            return;
        }
        var current = phase;
        if (current == null || current.isBlank()) {
            System.out.println(PREFIX + message);
        } else {
            System.out.println("[tool][" + current + "] " + message);
        }
    }

    public static void setPhase(String value) {
        phase = value;
    }

    public static void clearPhase() {
        phase = null;
    }

    private static boolean isTruthy(String value) {
        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("y");
    }
}
