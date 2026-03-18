package io.transmute.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI agent that performs cross-file reconciliation after all per-file recipes have run.
 * Reads the migration journal to understand what changed, then scans the output directory
 * for stale references (old class names, removed imports, outdated method calls) and fixes
 * them proactively — before the compile-fix loop.
 */
public interface ReconciliationAgent {

    @SystemMessage(fromResource = "reconciliation.md")
    @UserMessage("""
        Reconcile cross-file references in the migrated project at {{outputDir}}.

        ## Migration Journal
        {{journalContext}}

        ## Project Summary
        {{projectSummary}}

        ## Java Files in Output
        {{javaFileList}}

        Use outputDir-relative paths only (no absolute paths).
        Read files, fix stale references, and write the corrected files.
        Append a summary of your reconciliation changes to migration-journal.md using append_file.
        """)
    String reconcile(@V("outputDir") String outputDir,
                     @V("journalContext") String journalContext,
                     @V("projectSummary") String projectSummary,
                     @V("javaFileList") String javaFileList);
}
