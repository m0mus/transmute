package io.transmute.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI agent that fixes compilation errors in migrated code.
 * Built with {@code AiServices.builder(FixCompileErrorsAgent.class)} in {@code MigrationWorkflow}.
 */
public interface FixCompileErrorsAgent {

    @SystemMessage(fromResource = "fix-compile-errors.md")
    @UserMessage("""
        Fix compilation errors in the migrated project at {{outputDir}}.
        Errors: {{compileErrors}}

        ## Project Context
        {{projectSummary}}

        ## Migration Journal (context from earlier migration steps)
        {{journalContext}}

        Use outputDir-relative paths only (no absolute paths).
        Read each file with errors, fix the issues, and write the corrected files.
        """)
    String fix(@V("outputDir") String outputDir,
               @V("compileErrors") String errors,
               @V("projectSummary") String projectSummary,
               @V("journalContext") String journalContext);
}
