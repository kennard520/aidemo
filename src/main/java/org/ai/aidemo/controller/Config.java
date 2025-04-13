package org.ai.aidemo.controller;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import javax.swing.plaf.PanelUI;

@Configuration
public class Config {

    public static String model = "deepseek/deepseek-v3";

    @Bean
    public DeepSeekClient getDeepSeekClient() {
        return DeepSeekClient.builder().baseUrl("https://api.ppinfra.com/v3/openai").openAiApiKey("sk_nH_pAiFv3wR3bu2iCaqDb5-bbNnXhkYsxvwRrhY2hRc").model(model).build();

    }

    @Bean
    public WebClient deepSeekWebClient() {
        return WebClient.builder()
                .baseUrl("http://localhost:8080/chat/server") // DeepSeek API 地址
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
