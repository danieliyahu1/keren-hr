package com.akatsuki.kerenhr.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

@Slf4j
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        log.info("ChatClient wired with active ChatModel: {}", chatModel.getClass().getSimpleName());
        return ChatClient.builder(Objects.requireNonNull(chatModel, "chatModel is required")).build();
    }
}
