package io.transmute.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.generativeaiinference.model.ServingMode;

import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.transmute.agent.logging.PromptLogListener;

/**
 * Factory for creating {@link ChatModel} instances based on {@link TransmuteConfig}.
 * Supports OCI GenAI (default), OpenAI, and Ollama.
 */
public class ModelFactory {

    private static ChatModel instance;

    public static void configure(TransmuteConfig config) {
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
            configure(TransmuteConfig.defaults());
        }
        return instance;
    }

    private static ChatModel createOciGenAi(TransmuteConfig config) {
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

    private static ChatModel createOpenAi(TransmuteConfig config) {
        var normalizedBaseUrl = normalizeOpenAiBaseUrl(config.baseUrl());
        var builder = OpenAiChatModel.builder()
                .modelName(config.modelId("gpt-4o"))
                .timeout(config.modelTimeout(Duration.ofSeconds(60)));
        if (config.forceHttp1()) {
            builder.httpClientBuilder(new JdkHttpClientBuilder()
                    .httpClientBuilder(HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)));
        }
        if (config.apiKey() != null) {
            builder.apiKey(config.apiKey());
        }
        if (normalizedBaseUrl != null) {
            builder.baseUrl(normalizedBaseUrl);
        }
        if (PromptLogListener.enabled()) {
            builder.listeners(List.of(new PromptLogListener()));
        }
        return builder.build();
    }

    private static ChatModel createOllama(TransmuteConfig config) {
        var builder = OllamaChatModel.builder()
                .modelName(config.modelId("llama3.3"))
                .baseUrl(config.baseUrl("http://localhost:11434"));
        if (PromptLogListener.enabled()) {
            builder.listeners(List.of(new PromptLogListener()));
        }
        return builder.build();
    }

    private static String normalizeOpenAiBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        var trimmed = baseUrl.trim();
        if (trimmed.endsWith("/v1") || trimmed.contains("/v1/")) {
            return trimmed;
        }
        var uri = java.net.URI.create(trimmed);
        var path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return trimmed.endsWith("/") ? trimmed + "v1" : trimmed + "/v1";
        }
        return trimmed;
    }
}
