package com.akatsuki.kerenhr.zeroclaw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
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
@Component
public class ZeroClawChatModel implements ChatModel {

    private static final int TIMEOUT_SECONDS = 120;

    private final String wsUrl;
    private final ConcurrentHashMap<String, String> userSessions = new ConcurrentHashMap<>();

    public ZeroClawChatModel(@Value("${app.zeroclaw.ws-url}") String wsUrl) {
        this.wsUrl = wsUrl;
        log.info("ZeroClawChatModel initialized with wsUrl={}", wsUrl);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = prompt.getContents();
        String username = resolveAuthenticatedUsername();
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

        HttpClient httpClient = HttpClient.newHttpClient();

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
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to ZeroClaw WebSocket at " + uri, e);
        }

        // Wait for session_start before sending the message
        try {
            if (!sessionStartLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
        String payload = "{\"type\":\"message\",\"content\":" + jsonStringLiteral(userText) + "}";
        log.debug("Sending message to ZeroClaw, payloadLength={}", payload.length());
        ws.sendText(payload, true);

        // Await full response
        try {
            String result = responseFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

        String type = extractStringField(rawFrame, "type");
        log.trace("ZeroClaw frame type='{}' for user='{}'", type, username);

        switch (type) {
            case "session_start" -> {
                String sid = extractStringField(rawFrame, "session_id");
                if (sid != null && !sid.isBlank()) {
                    capturedSessionId.set(sid);
                    log.debug("ZeroClaw session_start received, session_id='{}'", sid);
                }
                sessionStartLatch.countDown();
            }
            case "chunk" -> {
                String content = extractStringField(rawFrame, "content");
                if (content != null) {
                    chunkBuffer.append(content);
                }
            }
            case "chunk_reset" -> {
                log.debug("ZeroClaw chunk_reset — clearing buffer");
                chunkBuffer.setLength(0);
            }
            case "done" -> {
                String fullResponse = extractStringField(rawFrame, "full_response");
                if (fullResponse == null || fullResponse.isBlank()) {
                    fullResponse = chunkBuffer.toString();
                }
                log.debug("ZeroClaw done frame, responseLength={}", fullResponse.length());
                responseFuture.complete(fullResponse);
            }
            case "error" -> {
                String message = extractStringField(rawFrame, "message");
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

    private String resolveAuthenticatedUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated username is required");
        }
        return authentication.getName();
    }

    /**
     * Minimal JSON string field extractor — avoids pulling in a JSON library.
     * Finds {@code "fieldName":"value"} or {@code "fieldName": "value"} patterns.
     */
    private static String extractStringField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx == -1) {
            return null;
        }
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx == -1) {
            return null;
        }
        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
            return null;
        }
        // Parse quoted string (handle \")
        StringBuilder sb = new StringBuilder();
        int i = valueStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(next);
                }
                i += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Wraps a string value as a JSON string literal (with escaping). */
    private static String jsonStringLiteral(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /** Thrown when ZeroClaw reports the session_id was not found, triggering a retry. */
    private static class SessionNotFoundException extends RuntimeException {
        SessionNotFoundException(String message) {
            super(message);
        }
    }
}
