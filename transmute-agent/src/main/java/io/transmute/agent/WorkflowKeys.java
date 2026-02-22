package io.transmute.agent;

/**
 * Centralized keys for AgenticScope state to avoid drift.
 */
public final class WorkflowKeys {
    public static final String SOURCE_DIR          = "sourceDir";
    public static final String OUTPUT_DIR          = "outputDir";
    public static final String INVENTORY           = "inventory";
    public static final String SKILLS_PLAN         = "skillsPlan";
    public static final String PLAN_APPROVAL       = "planApproval";
    public static final String HUMAN_APPROVAL      = "humanApproval";
    public static final String COMPILATION_SUCCESS = "compilationSuccess";
    public static final String COMPILE_ERRORS      = "compileErrors";
    public static final String ALL_TESTS_PASS      = "allTestsPass";
    public static final String TEST_OUTPUT         = "testOutput";
    public static final String REPORT              = "report";

    private WorkflowKeys() {}
}
