package com.akatsuki.kerenhr.zeroclaw;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring AI ChatModel backed by ZeroClaw's WebSocket chat endpoint.
 *
 * <p>Protocol:
 * <ol>
 *   <li>Connect to {@code ws://<host>/ws/chat} (no session_id) or
 *       {@code ws://<host>/ws/chat?session_id=<id>} (resume session).</li>
 *   <li>On {@code session_start} frame: store the session_id for this user.</li>
 *   <li>Send {@code {"type":"message","content":"<text>"}}.</li>
 *   <li>Collect {@code chunk} frames; reset buffer on {@code chunk_reset}.</li>
 *   <li>On {@code done} frame: return the full accumulated response.</li>
 *   <li>On {@code error} frame: throw {@link RuntimeException}.</li>
 *   <li>On session-not-found error: retry once without session_id.</li>
 * </ol>
 */
@Slf4j
@Primary
@Component
public class ZeroClawChatModel implements ChatModel {

    private final String wsUrl;
    private final int timeoutSeconds;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, String> userSessions = new ConcurrentHashMap<>();

    public ZeroClawChatModel(
            @Value("${app.zeroclaw.ws-url}") String wsUrl,
            @Value("${app.zeroclaw.timeout-seconds:600}") int timeoutSeconds) {
        this.wsUrl = wsUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        log.info("ZeroClawChatModel initialized with wsUrl={}, timeoutSeconds={}", wsUrl, timeoutSeconds);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = prompt.getContents();
        String username = resolveUsername(prompt);
        String existingSessionId = userSessions.get(username);

        log.debug("ZeroClaw call for user='{}', sessionId='{}', messageLength={}",
                username, existingSessionId, userText == null ? 0 : userText.length());

        String response = sendWithRetry(username, userText, existingSessionId);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String sendWithRetry(String username, String userText, String sessionId) {
        try {
            return doSend(username, userText, sessionId);
        } catch (SessionNotFoundException e) {
            log.warn("ZeroClaw session not found for user='{}', retrying with fresh session", username);
            userSessions.remove(username);
            return doSend(username, userText, null);
        }
    }

    private String doSend(String username, String userText, String sessionId) {
        URI uri = buildUri(sessionId);
        log.debug("Connecting to ZeroClaw WebSocket at {}", uri);

        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        StringBuilder chunkBuffer = new StringBuilder();
        AtomicReference<String> capturedSessionId = new AtomicReference<>(sessionId);
        CountDownLatch sessionStartLatch = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder frameBuffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                frameBuffer.append(data);
                if (last) {
                    String frame = frameBuffer.toString();
                    frameBuffer.setLength(0);
                    handleFrame(frame, ws, chunkBuffer, capturedSessionId, sessionStartLatch, responseFuture, username);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                ws.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                log.error("ZeroClaw WebSocket error for user='{}'", username, error);
                responseFuture.completeExceptionally(error);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                if (!responseFuture.isDone()) {
                    responseFuture.completeExceptionally(
                            new IllegalStateException("WebSocket closed unexpectedly: " + statusCode + " " + reason));
                }
                return null;
            }
        };

        WebSocket ws;
        try {
            ws = httpClient.newWebSocketBuilder()
                    .buildAsync(uri, listener)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to ZeroClaw WebSocket at " + uri, e);
        }

        // Wait for session_start before sending the message
        try {
            if (!sessionStartLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                ws.abort();
                throw new RuntimeException("Timed out waiting for ZeroClaw session_start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ws.abort();
            throw new RuntimeException("Interrupted waiting for ZeroClaw session_start", e);
        }

        // Store the (possibly new) session_id
        String resolvedSessionId = capturedSessionId.get();
        if (resolvedSessionId != null) {
            userSessions.put(username, resolvedSessionId);
        }

        // Send the message
        String payload = buildMessagePayload(userText);
        log.debug("Sending message to ZeroClaw, payloadLength={}", payload.length());
        ws.sendText(payload, true);

        // Await full response
        try {
            String result = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            return result;
        } catch (Exception e) {
            ws.abort();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SessionNotFoundException snfe) {
                throw snfe;
            }
            throw new RuntimeException("ZeroClaw response failed for user='" + username + "'", cause);
        }
    }

    private void handleFrame(
            String rawFrame,
            WebSocket ws,
            StringBuilder chunkBuffer,
            AtomicReference<String> capturedSessionId,
            CountDownLatch sessionStartLatch,
            CompletableFuture<String> responseFuture,
            String username) {

        JsonNode node;
        try {
            node = objectMapper.readTree(rawFrame);
        } catch (JsonProcessingException e) {
            log.warn("ZeroClaw unparseable frame for user='{}': {}", username, rawFrame);
            return;
        }

        String type = textOf(node, "type");
        log.debug("ZeroClaw raw frame for user='{}': {}",
                username, rawFrame.length() > 500 ? rawFrame.substring(0, 500) + "...(truncated)" : rawFrame);

        switch (type) {
            case "session_start" -> {
                String sid = textOf(node, "session_id");
                if (sid != null && !sid.isBlank()) {
                    capturedSessionId.set(sid);
                    log.debug("ZeroClaw session_start received, session_id='{}'", sid);
                }
                sessionStartLatch.countDown();
            }
            case "chunk" -> {
                String content = textOf(node, "content");
                if (content != null) {
                    chunkBuffer.append(content);
                }
            }
            case "chunk_reset" -> {
                log.debug("ZeroClaw chunk_reset — clearing buffer");
                chunkBuffer.setLength(0);
            }
            case "done" -> {
                String fullResponse = textOf(node, "full_response");
                if (fullResponse == null) {
                    fullResponse = chunkBuffer.toString();
                }
                if (fullResponse.isBlank()) {
                    log.warn("ZeroClaw returned empty response for user='{}' — agent could not complete the task", username);
                    fullResponse = "I was unable to complete this task. Please try breaking it into smaller steps or provide more details.";
                }
                log.debug("ZeroClaw done frame, responseLength={}", fullResponse.length());
                responseFuture.complete(fullResponse);
            }
            case "error" -> {
                String message = textOf(node, "message");
                if (message == null) {
                    message = rawFrame;
                }
                log.warn("ZeroClaw error frame for user='{}': {}", username, message);
                if (message.contains("session") && message.contains("not found")) {
                    responseFuture.completeExceptionally(new SessionNotFoundException(message));
                } else {
                    responseFuture.completeExceptionally(new RuntimeException("ZeroClaw error: " + message));
                }
            }
            default -> log.trace("ZeroClaw unhandled frame type='{}' for user='{}'", type, username);
        }
    }

    private URI buildUri(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return URI.create(wsUrl + "?session_id=" + sessionId);
        }
        return URI.create(wsUrl);
    }

    private String resolveUsername(@SuppressWarnings("unused") Prompt prompt) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && org.springframework.util.StringUtils.hasText(auth.getName())) {
            return auth.getName();
        }
        throw new IllegalStateException("Authenticated username is required");
    }

    private String buildMessagePayload(String userText) {
        try {
            return objectMapper.writeValueAsString(Map.of("type", "message", "content", userText == null ? "" : userText));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message payload", e);
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    /** Thrown when ZeroClaw reports the session_id was not found, triggering a retry. */
    private static class SessionNotFoundException extends RuntimeException {
        SessionNotFoundException(String message) {
            super(message);
        }
    }
}
