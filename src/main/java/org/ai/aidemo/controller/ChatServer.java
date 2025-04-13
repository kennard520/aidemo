package org.ai.aidemo.controller;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.chat.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static java.util.Arrays.asList;

@RestController
@Slf4j
public class ChatServer {

    @Autowired
    private DeepSeekClient deepSeekClient;

    io.github.pigmesh.ai.deepseek.core.chat.Function WEATHER_FUNCTION = io.github.pigmesh.ai.deepseek.core.chat.Function.builder()
            .name("get_knowledge_from_es")
            .description("Query Elasticsearch to retrieve knowledge about device maintenance or repair.")
            .parameters(JsonObjectSchema.builder()
                    .properties(new LinkedHashMap<String, JsonSchemaElement>() {{
                        put("query", JsonStringSchema.builder()
                                .description("The search query related to device maintenance or repair.")
                                .build());
                        put("size", JsonStringSchema.builder()
                                .description("The number of results to return.")
                                .build());
                    }})
                    .required(asList("location", "size"))
                    .build())
            .build();

    // 将 Function 转换为 Tool
    Tool WEATHER_TOOL = Tool.from(WEATHER_FUNCTION);

    @PostMapping(value = "/chat/server", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatCompletionResponse> chatAdvanced(@RequestBody RequestVo requestVo) {

        ChatCompletionRequest.Builder requestBuilder = ChatCompletionRequest.builder().model(Config.model)
                .addUserMessage(requestVo.getQuery())
                .addSystemMessage("You are a device maintenance and repair assistant. When the user asks" +
                        " about device maintenance or repair, call the get_knowledge_from_es function to retrieve " +
                        "relevant information from Elasticsearch. If the function is called, respond based on the" +
                        " function's return results; if the function is not called, respond directly" +
                        " If the response references knowledge from the knowledge base and sourceUrl has a" +
                        " value, add a footnote marker (e.g., [1]) in the text and append the footnote content " +
                        "in the format `[fileName](sourceUrl)` at the end of the response. If there is no sourceUrl," +
                        " do not add any footnotes. Always provide accurate and helpful responses");

        //需要查询es 拿到数据，并添加到消息中
        RequestVo.ToolCallVo toolCallVo = requestVo.getToolCallVo();
        if (Objects.nonNull(toolCallVo) && StringUtils.isNotBlank(toolCallVo.getToolCallId())) {
            String functionName = toolCallVo.getFunctionName();
            StringBuilder params = toolCallVo.getToolArguments();
            requestBuilder.addToolMessage(toolCallVo.getToolCallId(), toolCallVo.getToolCallResult().toString());
            //查询es，合并消息
        } else {
            requestBuilder.tools(WEATHER_TOOL);
        }
        ChatCompletionRequest request = requestBuilder.build();
        StringBuffer sb = new StringBuffer();
        return deepSeekClient.chatFluxCompletion(request).concatMap(r -> {
            List<ChatCompletionChoice> choices = r.choices();
            if (!CollectionUtils.isEmpty(choices))
                sb.append(choices.get(0).delta().content());
            return Flux.just(r);
        }).doFinally(r -> log.info(sb.toString())).timeout(Duration.ofSeconds(60)).onErrorResume(e -> {
            log.error("Tool call failed: {}", e.getMessage());
            return Flux.error(new RuntimeException("Tool call timeout"));
        });


    }
}
