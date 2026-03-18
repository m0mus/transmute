package io.transmute.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI agent that analyzes a project's structure and produces a high-level summary.
 * The summary is prepended to every recipe/feature prompt to give migration agents
 * awareness of the broader project context.
 */
public interface ProjectAnalysisAgent {

    @SystemMessage(fromResource = "project-analysis.md")
    @UserMessage("""
        Analyze this project and produce a structured summary.

        ## Project Inventory
        {{inventorySummary}}

        ## Key File Contents
        {{keyFileContents}}
        """)
    String analyze(@V("inventorySummary") String inventorySummary,
                   @V("keyFileContents") String keyFileContents);
}
