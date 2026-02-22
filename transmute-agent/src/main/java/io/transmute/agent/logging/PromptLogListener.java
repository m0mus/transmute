package io.transmute.agent.logging;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/**
 * Writes LLM prompts/responses to a JSONL log for offline analysis.
 */
public class PromptLogListener implements ChatModelListener {

    private static final int MAX_CHARS = 8000;
    private static final Path LOG_PATH = Path.of("logs", "ai-prompts.jsonl");

    public static boolean enabled() {
        var sys = System.getProperty("transmute.logPrompts");
        if (sys != null) {
            return isTruthy(sys);
        }
        var env = System.getenv("TRANSMUTE_LOG_PROMPTS");
        return env != null && isTruthy(env);
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        if (!enabled()) {
            return;
        }
        var payload = Map.of(
                "ts", Instant.now().toString(),
                "type", "request",
                "model", context.chatRequest().modelName(),
                "messages", safe(context.chatRequest().messages().toString())
        );
        writeJsonLine(payload);
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        if (!enabled()) {
            return;
        }
        var payload = Map.of(
                "ts", Instant.now().toString(),
                "type", "response",
                "model", context.chatRequest().modelName(),
                "response", safe(String.valueOf(context.chatResponse()))
        );
        writeJsonLine(payload);
    }

    public void onError(ChatModelRequestContext context, Throwable error) {
        if (!enabled()) {
            return;
        }
        var payload = Map.of(
                "ts", Instant.now().toString(),
                "type", "error",
                "model", context.chatRequest().modelName(),
                "error", safe(String.valueOf(error))
        );
        writeJsonLine(payload);
    }

    private void writeJsonLine(Map<String, String> payload) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            Files.writeString(LOG_PATH, toJsonLine(payload),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private String toJsonLine(Map<String, String> payload) {
        var sb = new StringBuilder();
        sb.append("{");
        var first = true;
        for (var entry : payload.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":");
            sb.append("\"").append(escape(entry.getValue())).append("\"");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        var truncated = value.length() > MAX_CHARS
                ? value.substring(0, MAX_CHARS) + "...(truncated)"
                : value;
        return redact(truncated);
    }

    private String redact(String value) {
        return value.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)([^\\s,\\\"]+)", "$1***");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static boolean isTruthy(String value) {
        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("y");
    }
}
