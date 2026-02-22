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
 * AI agent that fixes compilation errors in migrated code.
 *
 * <p>Used inside the compile-fix loop:
 * {@code AgenticServices.loopBuilder().subAgents(compileStep, fixCompileAgent)...}
 */
public interface FixCompileErrorsAgent {

    @SystemMessage(fromResource = "prompts/fix-compile-errors.md")
    @UserMessage("""
        Fix compilation errors in the migrated project at {{outputDir}}.
        Errors: {{compileErrors}}

        Use outputDir-relative paths only (no absolute paths).
        Read each file with errors, fix the issues, and write the corrected files.
        """)
    @Agent(outputKey = "fixResult",
           description = "Fixes compilation errors in migrated code")
    String fix(@V("outputDir") String outputDir,
               @V("compileErrors") String errors);

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
