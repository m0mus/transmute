package io.transmute.agent;

import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.generativeaiinference.model.ServingMode;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.transmute.agent.logging.PromptLogListener;

import java.util.List;

/**
 * Factory for creating {@link ChatModel} instances based on {@link MigrationConfig}.
 * Supports OCI GenAI (default), OpenAI, and Ollama.
 */
public class ModelFactory {

    private static ChatModel instance;

    public static void configure(MigrationConfig config) {
        instance = switch (config.modelProvider()) {
            case "oci-genai" -> createOciGenAi(config);
            case "openai"    -> createOpenAi(config);
            case "ollama"    -> createOllama(config);
            default -> throw new IllegalArgumentException(
                    "Unknown model provider: " + config.modelProvider()
                    + ". Supported: oci-genai, openai, ollama");
        };
    }

    public static ChatModel create() {
        if (instance == null) {
            configure(MigrationConfig.defaults());
        }
        return instance;
    }

    private static ChatModel createOciGenAi(MigrationConfig config) {
        var builder = OciGenAiChatModel.builder()
                .modelName(config.modelId("cohere.command-r-plus"))
                .servingType(ServingMode.ServingType.OnDemand);
        if (config.ociProfile() != null && !config.ociProfile().isBlank()) {
            try {
                var provider = new ConfigFileAuthenticationDetailsProvider(config.ociProfile());
                builder.authProvider(provider);
            } catch (Exception e) {
                System.err.println("Warning: failed to load OCI profile '"
                        + config.ociProfile() + "': " + e.getMessage());
            }
        }
        return builder.build();
    }

    private static ChatModel createOpenAi(MigrationConfig config) {
        var builder = OpenAiChatModel.builder()
                .modelName(config.modelId("gpt-4o"));
        if (config.apiKey() != null) {
            builder.apiKey(config.apiKey());
        }
        if (config.baseUrl() != null) {
            builder.baseUrl(config.baseUrl());
        }
        if (PromptLogListener.enabled()) {
            builder.listeners(List.of(new PromptLogListener()));
        }
        return builder.build();
    }

    private static ChatModel createOllama(MigrationConfig config) {
        var builder = OllamaChatModel.builder()
                .modelName(config.modelId("llama3.3"))
                .baseUrl(config.baseUrl("http://localhost:11434"));
        if (PromptLogListener.enabled()) {
            builder.listeners(List.of(new PromptLogListener()));
        }
        return builder.build();
    }
}
