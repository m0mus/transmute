package io.transmute.migration.postcheck;

/**
 * The outcome of a single {@link PostcheckRule} evaluation.
 */
public record PostcheckResult(boolean passed, String message) {

    public static PostcheckResult pass() {
        return new PostcheckResult(true, "");
    }

    public static PostcheckResult fail(String message) {
        return new PostcheckResult(false, message);
    }
}
