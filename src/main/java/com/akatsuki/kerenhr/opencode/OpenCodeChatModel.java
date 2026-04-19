package com.akatsuki.kerenhr.opencode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Spring AI {@link ChatModel} backed by the OpenCode Gateway HTTP API.
 *
 * <p>Stateless: every {@link #call(Prompt)} creates a fresh OpenCode session, sends the
 * prompt, reads the response, then deletes the session. No state is retained between calls.
 *
 * <p>Protocol per call:
 * <ol>
 *   <li>Create a session via {@code POST /session}.</li>
 *   <li>Send the prompt via {@code POST /session/{id}/message} (streaming NDJSON response).</li>
 *   <li>Filter response parts by {@code type == "text"} to exclude reasoning/thinking parts.</li>
 *   <li>Delete the session via {@code DELETE /session/{id}} (best-effort cleanup).</li>
 * </ol>
 */
@Slf4j
@Primary
@Component
public class OpenCodeChatModel implements ChatModel {

    private final String baseUrl;
    private final int timeoutSeconds;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenCodeChatModel(
            @Value("${app.opencode.base-url}") String baseUrl,
            @Value("${app.opencode.timeout-seconds:600}") int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();
        this.objectMapper = new ObjectMapper();
        log.debug("OpenCodeChatModel initialized with baseUrl={}, timeoutSeconds={}", baseUrl, timeoutSeconds);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = prompt.getContents();
        log.debug("OpenCode call messageLength={}", userText == null ? 0 : userText.length());

        String sessionId = createSession();
        try {
            String response = sendMessage(sessionId, userText);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        } finally {
            deleteSession(sessionId);
        }
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    private String createSession() {
        log.info("OpenCode creating new session");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = execute(request);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "OpenCode session creation failed with HTTP " + response.statusCode() +
                    ": " + response.body());
        }

        try {
            JsonNode node = objectMapper.readTree(response.body());
            String sessionId = textOf(node, "id");
            if (sessionId == null || sessionId.isBlank()) {
                throw new RuntimeException(
                        "OpenCode session creation response missing 'id' field: " + response.body());
            }
            log.info("OpenCode session created sessionId='{}'", sessionId);
            return sessionId;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to parse OpenCode session creation response: " + response.body(), e);
        }
    }

    private void deleteSession(String sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/session/" + sessionId))
                    .DELETE()
                    .build();
            HttpResponse<String> response = execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OpenCode session delete returned HTTP {} for sessionId='{}'",
                        response.statusCode(), sessionId);
            } else {
                log.info("OpenCode session deleted sessionId='{}'", sessionId);
            }
        } catch (Exception e) {
            log.warn("OpenCode session delete failed for sessionId='{}': {}", sessionId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Message sending via synchronous POST /session/{id}/message
    // -------------------------------------------------------------------------

    private String sendMessage(String sessionId, String userText) {
        String body = buildPromptBody(userText);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/" + sessionId + "/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        log.info("OpenCode sending message sessionId='{}', payloadLength={}", sessionId, body.length());

        HttpResponse<java.io.InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Failed to connect to OpenCode gateway at " + baseUrl + ": " + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "OpenCode message returned HTTP " + response.statusCode() +
                    " for sessionId='" + sessionId + "'");
        }

        String text = readTextPartsFromStream(response.body(), sessionId);
        log.info("OpenCode task completed sessionId='{}', responseLength={}", sessionId, text.length());
        return text;
    }

    /**
     * Reads the NDJSON response stream line-by-line, accumulating only {@code type=text} parts
     * and stopping as soon as a {@code type=step-finish} part is received — matching the
     * behaviour of the main-branch WebClient {@code bodyToFlux} approach.
     *
     * <p>Reasoning/thinking parts ({@code type=reasoning}) are intentionally skipped.
     */
    private String readTextPartsFromStream(java.io.InputStream body, String sessionId) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonNode node = objectMapper.readTree(line);
                    JsonNode parts = node.get("parts");
                    if (parts == null || !parts.isArray()) continue;
                    for (JsonNode part : parts) {
                        String partType = textOf(part, "type");
                        if ("text".equals(partType)) {
                            String partText = textOf(part, "text");
                            if (partText != null) text.append(partText);
                        } else if ("step-finish".equals(partType)) {
                            log.debug("OpenCode step-finish received for session='{}'", sessionId);
                            return text.isEmpty()
                                ? "I was unable to complete this task. Please try breaking it into smaller steps or provide more details."
                                : text.toString();
                        }
                    }
                } catch (JsonProcessingException e) {
                    log.trace("OpenCode skipping unparseable line for session='{}': {}", sessionId, line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading OpenCode response stream for session='" + sessionId + "'", e);
        }

        if (text.isEmpty()) {
            log.warn("OpenCode stream ended with no text parts for sessionId='{}'", sessionId);
            return "I was unable to complete this task. Please try breaking it into smaller steps or provide more details.";
        }
        return text.toString();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Failed to connect to OpenCode gateway at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private String buildPromptBody(String userText) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("parts", List.of(
                            Map.of("type", "text", "text", userText == null ? "" : userText)
                    ))
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OpenCode prompt payload", e);
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}
