package com.akatsuki.kerenhr.controller;

import com.akatsuki.kerenhr.dto.ChatRequest;
import com.akatsuki.kerenhr.dto.ChatResponse;
import com.akatsuki.kerenhr.service.KerenHrChatService;
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

    public KerenHrChatController(KerenHrChatService kerenHrChatService) {
        this.kerenHrChatService = kerenHrChatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        log.info("POST /api/kerenhr/chat request received");
        String content = kerenHrChatService.chat(request.message());
        log.debug("Chat response generated, length={}", content == null ? 0 : content.length());
        return new ChatResponse(content);
    }
}