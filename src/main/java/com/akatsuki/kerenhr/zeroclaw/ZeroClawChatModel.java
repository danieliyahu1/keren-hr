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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Safety ceiling — prevents a looping agent from holding a session open forever. */
    private static final int MAX_TURNS = 20;

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
        log.info("ZeroClaw connecting for user='{}', uri={}", username, uri);

        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        StringBuilder chunkBuffer = new StringBuilder();
        AtomicReference<String> capturedSessionId = new AtomicReference<>(sessionId);
        CountDownLatch sessionStartLatch = new CountDownLatch(1);

        // Multi-turn state: track whether the current turn used any tools (meaning the
        // agent is still working) and how many continuation turns have been sent.
        AtomicBoolean hadToolCallsThisTurn = new AtomicBoolean(false);
        AtomicInteger turnCount = new AtomicInteger(0);

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder frameBuffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                frameBuffer.append(data);
                if (last) {
                    String frame = frameBuffer.toString();
                    frameBuffer.setLength(0);
                    handleFrame(frame, ws, chunkBuffer, capturedSessionId, sessionStartLatch,
                            responseFuture, username, hadToolCallsThisTurn, turnCount);
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
                    log.warn("ZeroClaw WebSocket closed unexpectedly for user='{}': status={} reason={}",
                            username, statusCode, reason);
                    responseFuture.completeExceptionally(
                            new IllegalStateException(
                                    "ZeroClaw WebSocket closed unexpectedly (status=" + statusCode + ", reason=" + reason + "). " +
                                    "The agent may have been interrupted mid-task."));
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
            throw new RuntimeException(
                    "Failed to connect to ZeroClaw WebSocket at " + uri + ". " +
                    "Check that the ZeroClaw service is running.", e);
        }

        // Wait for session_start before sending the message
        try {
            if (!sessionStartLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                ws.abort();
                throw new RuntimeException(
                        "Timed out waiting for ZeroClaw session_start after " + timeoutSeconds + "s. " +
                        "The agent service may be overloaded or unreachable.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ws.abort();
            throw new RuntimeException("Interrupted while waiting for ZeroClaw session_start", e);
        }

        // Store the (possibly new) session_id
        String resolvedSessionId = capturedSessionId.get();
        if (resolvedSessionId != null) {
            userSessions.put(username, resolvedSessionId);
            log.info("ZeroClaw session established for user='{}', sessionId='{}'", username, resolvedSessionId);
        }

        // Send the initial message
        String payload = buildMessagePayload(userText);
        log.info("ZeroClaw sending initial message for user='{}', payloadLength={}", username, payload.length());
        ws.sendText(payload, true);

        // Await the full multi-turn response
        try {
            String result = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            log.info("ZeroClaw task completed for user='{}' after {} turn(s)", username, turnCount.get() + 1);
            return result;
        } catch (Exception e) {
            ws.abort();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SessionNotFoundException snfe) {
                throw snfe;
            }
            if (cause instanceof java.util.concurrent.TimeoutException) {
                throw new RuntimeException(
                        "ZeroClaw timed out after " + timeoutSeconds + "s for user='" + username + "'. " +
                        "The agent is taking too long — try a simpler request or increase the timeout.", cause);
            }
            throw new RuntimeException(
                    "ZeroClaw task failed for user='" + username + "': " + cause.getMessage(), cause);
        }
    }

    private void handleFrame(
            String rawFrame,
            WebSocket ws,
            StringBuilder chunkBuffer,
            AtomicReference<String> capturedSessionId,
            CountDownLatch sessionStartLatch,
            CompletableFuture<String> responseFuture,
            String username,
            AtomicBoolean hadToolCallsThisTurn,
            AtomicInteger turnCount) {

        JsonNode node;
        try {
            node = objectMapper.readTree(rawFrame);
        } catch (JsonProcessingException e) {
            log.warn("ZeroClaw unparseable frame for user='{}': {}", username, rawFrame);
            return;
        }

        String type = textOf(node, "type");
        log.debug("ZeroClaw frame type='{}' for user='{}'", type, username);

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
            case "tool_call" -> {
                // The agent is executing a tool — it has more work to do after this turn.
                String toolName = textOf(node, "name");
                log.info("ZeroClaw tool_call '{}' for user='{}' (turn {})",
                        toolName, username, turnCount.get() + 1);
                hadToolCallsThisTurn.set(true);
            }
            case "tool_result" -> {
                String toolName = textOf(node, "name");
                log.debug("ZeroClaw tool_result '{}' for user='{}'", toolName, username);
            }
            case "done" -> {
                String fullResponse = textOf(node, "full_response");
                if (fullResponse == null) {
                    fullResponse = chunkBuffer.toString();
                }

                int currentTurn = turnCount.get() + 1;

                if (hadToolCallsThisTurn.get() && currentTurn < MAX_TURNS) {
                    // Agent used tools this turn — it is still working. Send a continuation.
                    int nextTurn = turnCount.incrementAndGet() + 1;
                    hadToolCallsThisTurn.set(false);
                    chunkBuffer.setLength(0);

                    log.info("ZeroClaw turn {} complete with tool calls — sending continuation for user='{}' (turn {}/{})",
                            currentTurn, username, nextTurn, MAX_TURNS);

                    String continuePayload = buildMessagePayload("continue");
                    ws.sendText(continuePayload, true);

                } else if (hadToolCallsThisTurn.get() && currentTurn >= MAX_TURNS) {
                    // Safety ceiling reached — stop and return what we have.
                    // fullResponse is guaranteed non-null here (assigned from full_response field
                    // or chunkBuffer.toString() above), so only a blank check is needed.
                    log.warn("ZeroClaw reached MAX_TURNS ({}) for user='{}' — stopping. " +
                            "The agent may not have fully completed the task.", MAX_TURNS, username);
                    if (fullResponse.isBlank()) {
                        fullResponse = "The agent reached the maximum number of steps (" + MAX_TURNS +
                                ") without completing the task. Please try breaking the request into smaller parts.";
                    }
                    log.info("ZeroClaw returning partial result after {} turns for user='{}'", MAX_TURNS, username);
                    responseFuture.complete(fullResponse);

                } else {
                    // No tool calls this turn — the agent gave a final text answer. We're done.
                    if (fullResponse == null || fullResponse.isBlank()) {
                        log.warn("ZeroClaw returned empty response for user='{}' after {} turn(s) — " +
                                "agent could not complete the task", username, currentTurn);
                        fullResponse = "I was unable to complete this task. Please try breaking it into smaller steps or provide more details.";
                    }
                    log.info("ZeroClaw task finished for user='{}' after {} turn(s), responseLength={}",
                            username, currentTurn, fullResponse.length());
                    responseFuture.complete(fullResponse);
                }
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
                    responseFuture.completeExceptionally(
                            new RuntimeException("ZeroClaw agent error: " + message +
                                    " — please try again or rephrase your request."));
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
