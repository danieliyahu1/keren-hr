package com.akatsuki.kerenhr.config;

import com.akatsuki.kerenhr.zeroclaw.ZeroClawChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

@Slf4j
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ZeroClawChatModel zeroClawChatModel) {
        log.debug("Creating ChatClient bean backed by ZeroClawChatModel");
        return ChatClient.builder(Objects.requireNonNull(zeroClawChatModel, "zeroClawChatModel is required")).build();
    }
}
