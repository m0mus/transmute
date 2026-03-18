package io.transmute.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI agent that fixes test failures in migrated code.
 * Built with {@code AiServices.builder(FixTestFailuresAgent.class)} in {@code MigrationWorkflow}.
 */
public interface FixTestFailuresAgent {

    @SystemMessage(fromResource = "fix-test-failures.md")
    @UserMessage("""
        Fix test failures in the migrated project at {{outputDir}}.
        Test output: {{testOutput}}

        ## Project Context
        {{projectSummary}}

        ## Migration Journal (context from earlier migration steps)
        {{journalContext}}

        Use outputDir-relative paths only (no absolute paths).
        Read the failing test files and the code they test, fix the issues,
        and write the corrected files.
        """)
    String fix(@V("outputDir") String outputDir,
               @V("testOutput") String testOutput,
               @V("projectSummary") String projectSummary,
               @V("journalContext") String journalContext);
}
