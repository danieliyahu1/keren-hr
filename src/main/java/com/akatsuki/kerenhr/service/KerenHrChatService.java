package com.akatsuki.kerenhr.service;

import com.akatsuki.kerenhr.opencode.OpenCodeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Slf4j
@Service
public class KerenHrChatService {

    private static final int LOG_PREVIEW_MAX = 2000;

    private final ChatClient chatClient;
    private final OpenCodeChatModel openCodeChatModel;

    public KerenHrChatService(ChatClient chatClient, OpenCodeChatModel openCodeChatModel) {
        this.chatClient = chatClient;
        this.openCodeChatModel = openCodeChatModel;
    }

    /**
     * Stateless chat: creates a fresh OpenCode session per call (original behaviour).
     *
     * @deprecated Use {@link #chat(String, String)} for stateful session-aware chat instead.
     */
    @Deprecated
    @SuppressWarnings("null")
    public String chat(String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message is required");
        }

        String safeMessage = Objects.requireNonNull(message, "message is required").trim();
        log.info("Sending chat message, length={}", safeMessage.length());
        log.debug("User -> model: {}", previewForLog(safeMessage));
        String response = chatClient.prompt()
            .user(safeMessage)
            .call()
            .content();
        log.info("Received chat response, length={}", response == null ? 0 : response.length());
        log.debug("Model -> user: {}", previewForLog(response));
        return response;
    }

    /**
     * Stateful chat: sends a message to an existing OpenCode session.
     * The session is not created or deleted here — the controller manages the lifecycle.
     *
     * @param openCodeSessionId an active OpenCode session ID
     * @param message           the user message text
     * @return the assistant response text
     */
    public String chat(String openCodeSessionId, String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message is required");
        }

        String safeMessage = message.trim();
        log.info("Stateful chat → openCodeSession='{}' messageLength={}", openCodeSessionId, safeMessage.length());
        log.debug("User → model [openCodeSession='{}']: {}", openCodeSessionId, previewForLog(safeMessage));
        String response = openCodeChatModel.chat(openCodeSessionId, safeMessage);
        log.info("Stateful chat ← openCodeSession='{}' responseLength={}", openCodeSessionId, response == null ? 0 : response.length());
        log.debug("Model → user [openCodeSession='{}']: {}", openCodeSessionId, previewForLog(response));
        return response;
    }

    private String previewForLog(String text) {
        if (text == null) {
            return "(null)";
        }

        String normalized = text.replace("\r", "\\r").replace("\n", "\\n");
        if (normalized.length() <= LOG_PREVIEW_MAX) {
            return normalized;
        }

        return normalized.substring(0, LOG_PREVIEW_MAX) + "...(truncated)";
    }
}
