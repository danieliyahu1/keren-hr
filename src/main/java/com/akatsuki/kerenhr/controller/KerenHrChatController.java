package com.akatsuki.kerenhr.controller;

import com.akatsuki.kerenhr.dto.ChatRequest;
import com.akatsuki.kerenhr.dto.ChatResponse;
import com.akatsuki.kerenhr.opencode.OpenCodeChatModel;
import com.akatsuki.kerenhr.service.KerenHrChatService;
import com.akatsuki.kerenhr.session.SessionConstants;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/kerenhr")
public class KerenHrChatController {

    private final KerenHrChatService kerenHrChatService;
    private final OpenCodeChatModel openCodeChatModel;

    public KerenHrChatController(KerenHrChatService kerenHrChatService, OpenCodeChatModel openCodeChatModel) {
        this.kerenHrChatService = kerenHrChatService;
        this.openCodeChatModel = openCodeChatModel;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpSession session) {
        // Log full request context up front — useful to grep per HTTP session
        log.info("POST /api/kerenhr/chat httpSession={} isNew={} messageLength={}",
                session.getId(), session.isNew(), request.message().length());

        String openCodeSessionId = (String) session.getAttribute(SessionConstants.OPENCODE_SESSION_ATTR);

        if (openCodeSessionId == null) {
            log.info("No OpenCode session on httpSession={} — creating one", session.getId());
            openCodeSessionId = openCodeChatModel.createSession();
            session.setAttribute(SessionConstants.OPENCODE_SESSION_ATTR, openCodeSessionId);
            log.info("OpenCode session bound: httpSession={} → openCodeSession='{}'",
                    session.getId(), openCodeSessionId);
        } else {
            log.info("Resuming existing OpenCode session: httpSession={}, openCodeSession='{}'",
                    session.getId(), openCodeSessionId);
        }

        String content = kerenHrChatService.chat(openCodeSessionId, request.message());

        int responseLength = content == null ? 0 : content.length();
        log.info("POST /api/kerenhr/chat completed httpSession={} openCodeSession='{}' responseLength={}",
                session.getId(), openCodeSessionId, responseLength);

        return new ChatResponse(content);
    }
}
