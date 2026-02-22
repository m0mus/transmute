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
 * AI agent that generates a {@link io.transmute.skill.MigrationSkill} implementation
 * from a before/after file pair, producing a new skill class the user can review and refine.
 */
public interface SkillGeneratorAgent {

    @SystemMessage(fromResource = "prompts/generate-skill.md")
    @UserMessage("""
        Generate a MigrationSkill implementation based on the following example.

        Before file (source framework code):
        {{beforeFile}}

        After file (target framework code):
        {{afterFile}}

        Skill package: {{skillPackage}}

        Produce a complete, compilable Java class implementing MigrationSkill
        with appropriate @Skill, @Trigger, and optional @Postchecks annotations.
        """)
    @Agent(outputKey = "generatedSkill",
           description = "Generates a MigrationSkill from a before/after example pair")
    String generate(
            @V("beforeFile") String beforeFile,
            @V("afterFile") String afterFile,
            @V("skillPackage") String skillPackage);

    @ChatModelSupplier
    static ChatModel chatModel() {
        return ModelFactory.create();
    }

    @ToolsSupplier
    static Object[] tools() {
        return new MigrationTools(null, null).codeEditTools().toArray();
    }
}
