package io.transmute.agent.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatModelSupplier;
import dev.langchain4j.agentic.declarative.ToolsSupplier;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.transmute.agent.ModelFactory;
import io.transmute.tool.MigrationTools;

/**
 * AI agent that fixes test failures in migrated code.
 *
 * <p>Used inside the test-fix loop:
 * {@code AgenticServices.loopBuilder().subAgents(testStep, fixTestAgent)...}
 */
public interface FixTestFailuresAgent {

    @SystemMessage(fromResource = "prompts/fix-test-failures.md")
    @UserMessage("""
        Fix test failures in the migrated project at {{outputDir}}.
        Test output: {{testOutput}}

        Use outputDir-relative paths only (no absolute paths).
        Read the failing test files and the code they test, fix the issues,
        and write the corrected files.
        """)
    @Agent(outputKey = "fixResult",
           description = "Fixes test failures in migrated code")
    String fix(@V("outputDir") String outputDir,
               @V("testOutput") String testOutput);

    @ChatModelSupplier
    static ChatModel chatModel() {
        return ModelFactory.create();
    }

    @ToolsSupplier
    static Object[] tools() {
        return new MigrationTools(AgentToolsConfig.outputDir, AgentToolsConfig.activeProfiles)
                .codeEditTools().toArray();
    }
}
