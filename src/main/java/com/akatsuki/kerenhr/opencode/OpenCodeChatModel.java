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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI {@link ChatModel} backed by the OpenCode Gateway HTTP API.
 *
 * <p>Protocol:
 * <ol>
 *   <li>Create a session via {@code POST /session} (once per user; reused across calls).</li>
 *   <li>Send the prompt via {@code POST /session/{id}/message} (synchronous, returns full response).</li>
 *   <li>Filter response parts by {@code type == "text"} to exclude reasoning/thinking parts.</li>
 *   <li>On HTTP 404 from {@code /message}: evict the stale session and retry once.</li>
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

    /** Maps authenticated username → OpenCode session ID for conversation continuity. */
    private final ConcurrentHashMap<String, String> userSessions = new ConcurrentHashMap<>();

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
        String username = resolveUsername();
        String existingSessionId = userSessions.get(username);

        log.debug("OpenCode call for user='{}', sessionId='{}', messageLength={}",
                username, existingSessionId, userText == null ? 0 : userText.length());

        String response = sendWithRetry(username, userText, existingSessionId);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
    }

    // -------------------------------------------------------------------------
    // Retry wrapper
    // -------------------------------------------------------------------------

    private String sendWithRetry(String username, String userText, String sessionId) {
        try {
            return doSend(username, userText, sessionId);
        } catch (SessionNotFoundException e) {
            log.warn("OpenCode session not found for user='{}', retrying with fresh session", username);
            userSessions.remove(username);
            return doSend(username, userText, null);
        }
    }

    private String doSend(String username, String userText, String existingSessionId) {
        String sessionId = ensureSession(username, existingSessionId);
        userSessions.put(username, sessionId);
        log.info("OpenCode session established for user='{}', sessionId='{}'", username, sessionId);

        return sendMessage(username, sessionId, userText);
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    private String ensureSession(String username, String existingSessionId) {
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            log.debug("OpenCode reusing session '{}' for user='{}'", existingSessionId, username);
            return existingSessionId;
        }
        return createSession(username);
    }

    private String createSession(String username) {
        log.info("OpenCode creating new session for user='{}'", username);
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
            log.info("OpenCode new session created for user='{}', sessionId='{}'", username, sessionId);
            return sessionId;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to parse OpenCode session creation response: " + response.body(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Message sending via synchronous POST /session/{id}/message
    // -------------------------------------------------------------------------

    private String sendMessage(String username, String sessionId, String userText) {
        String body = buildPromptBody(userText);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/" + sessionId + "/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        log.info("OpenCode sending message for user='{}', sessionId='{}', payloadLength={}",
                username, sessionId, body.length());

        HttpResponse<java.io.InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Failed to connect to OpenCode gateway at " + baseUrl + ": " + e.getMessage(), e);
        }

        if (response.statusCode() == 404) {
            throw new SessionNotFoundException(
                    "OpenCode session '" + sessionId + "' not found (HTTP 404)");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "OpenCode message returned HTTP " + response.statusCode() +
                    " for sessionId='" + sessionId + "'");
        }

        String text = readTextPartsFromStream(response.body(), sessionId);
        log.info("OpenCode task completed for user='{}', responseLength={}", username, text.length());
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

    private String resolveUsername() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && org.springframework.util.StringUtils.hasText(auth.getName())) {
            return auth.getName();
        }
        throw new IllegalStateException("Authenticated username is required");
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

    /** Thrown when OpenCode returns 404 for a session, triggering a retry with a fresh session. */
    private static class SessionNotFoundException extends RuntimeException {
        SessionNotFoundException(String message) {
            super(message);
        }
    }
}
