package org.ai.aidemo;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.chat.*;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.ai.aidemo.controller.Config;
import org.ai.aidemo.proto.ChatServiceGrpc;
import org.ai.aidemo.proto.GrpcChatCompletionResponse;
import org.ai.aidemo.proto.GrpcChatRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static java.util.Arrays.asList;

@Slf4j
@GrpcService
public class ChatServiceImpl extends ChatServiceGrpc.ChatServiceImplBase {

    private final DeepSeekClient deepSeekClient;


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

    public ChatServiceImpl(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    @Override
    public void chatStream(GrpcChatRequest request, StreamObserver<GrpcChatCompletionResponse> responseObserver) {
        ChatCompletionRequest chatCompletionRequest = convertToChatCompletionRequest(request);
        // 2. 调用原有业务逻辑
        StringBuffer sb = new StringBuffer();
        Flux<GrpcChatCompletionResponse> chatCompletionResponseFlux = deepSeekClient.chatFluxCompletion(chatCompletionRequest)
                .map(this::convertToGrpcResponse)
                .concatMap(r -> {
                    List<GrpcChatCompletionResponse.GrpcChatCompletionChoice> choices = r.getChoicesList();
                    if (!CollectionUtils.isEmpty(choices))
                        sb.append(choices.get(0).getDelta().getContent());
                    return Flux.just(r);
                }).doFinally(r -> log.info(sb.toString())).timeout(Duration.ofSeconds(60)).onErrorResume(e -> {
                    log.error("Tool call failed: {}", e.getMessage(), e);
                    return Flux.error(new RuntimeException("Tool call timeout"));
                });
        chatCompletionResponseFlux.subscribe(responseObserver::onNext, responseObserver::onError, responseObserver::onCompleted);
    }

    private GrpcChatCompletionResponse convertToGrpcResponse(io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionResponse original) {
        GrpcChatCompletionResponse.Builder builder = GrpcChatCompletionResponse.newBuilder()
                .setId(original.id())
                .setCreated(original.created())
                .setModel(original.model())
                .setSystemFingerprint(original.systemFingerprint())
                .setServiceTier(original.serviceTier() == null ? "" : original.serviceTier());

        if (original.usage() != null) {
            GrpcChatCompletionResponse.GrpcUsage.Builder usageBuilder = GrpcChatCompletionResponse.GrpcUsage.newBuilder()
                    .setTotalTokens(original.usage().totalTokens())
                    .setPromptTokens(original.usage().promptTokens())
                    .setCompletionTokens(original.usage().completionTokens());

            if (original.usage().promptTokensDetails() != null) {
                usageBuilder.setPromptTokensDetails(
                        GrpcChatCompletionResponse.GrpcUsage.GrpcPromptTokensDetails.newBuilder()
                                .setCachedTokens(original.usage().promptTokensDetails().cachedTokens())
                                .build()
                );
            }

            if (original.usage().completionTokensDetails() != null) {
                usageBuilder.setCompletionTokensDetails(
                        GrpcChatCompletionResponse.GrpcUsage.GrpcCompletionTokensDetails.newBuilder()
                                .setReasoningTokens(original.usage().completionTokensDetails().reasoningTokens())
                                .build()
                );
            }

            builder.setUsage(usageBuilder.build());
        }
        for (ChatCompletionChoice choice : original.choices()) {
            GrpcChatCompletionResponse.GrpcChatCompletionChoice.Builder choiceBuilder =
                    GrpcChatCompletionResponse.GrpcChatCompletionChoice.newBuilder()
                            .setIndex(choice.index())
                            .setFinishReason(choice.finishReason() == null ? "" : choice.finishReason());

            if (choice.message() != null) {
                choiceBuilder.setMessage(convertAssistantMessage(choice.message()));
            }

            if (choice.delta() != null) {
                choiceBuilder.setDelta(convertDelta(choice.delta()));
            }

            builder.addChoices(choiceBuilder.build());
        }


        return builder.build();
    }

    private GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcAssistantMessage convertAssistantMessage(
            AssistantMessage message) {
        GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcAssistantMessage.Builder builder =
                GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcAssistantMessage.newBuilder()
                        .setRole(convertRole(message.role()))
                        .setContent(message.content())
                        .setReasoningContent(message.reasoningContent())
                        .setName(message.name())
                        .setRefusal(message.refusal());

        if (message.functionCall() != null) {
            builder.setFunctionCall(
                    GrpcChatCompletionResponse.GrpcToolCall.GrpcFunctionCall.newBuilder()
                            .setName(message.functionCall().name() == null ? "" : message.functionCall().name())
                            .setArguments(message.functionCall().arguments() == null ? "" : message.functionCall().arguments().toString())
                            .build());
        }

        for (ToolCall toolCall : message.toolCalls()) {
            builder.addToolCalls(convertToolCall(toolCall));
        }

        return builder.build();
    }

    private GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcDelta convertDelta(
            Delta delta) {
        GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcDelta.Builder builder =
                GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcDelta.newBuilder()
                        .setRole(convertRole(delta.role()))
                        .setContent(delta.content() == null ? "" : delta.content())
                        .setReasoningContent(delta.reasoningContent() == null ? "" : delta.reasoningContent());

        if (delta.functionCall() != null) {
            builder.setFunctionCall(
                    GrpcChatCompletionResponse.GrpcToolCall.GrpcFunctionCall.newBuilder()
                            .setName(delta.functionCall().name() == null ? "" : delta.functionCall().name())
                            .setArguments(delta.functionCall().arguments() == null ? "" : delta.functionCall().arguments())
                            .build()
            );
        }
        if (delta.toolCalls() != null) {
            for (ToolCall toolCall : delta.toolCalls()) {
                builder.addToolCalls(convertToolCall(toolCall));
            }
        }
        return builder.build();
    }

    private GrpcChatCompletionResponse.GrpcToolCall convertToolCall(ToolCall toolCall) {
        GrpcChatCompletionResponse.GrpcToolCall.Builder builder =
                GrpcChatCompletionResponse.GrpcToolCall.newBuilder()
                        .setId(toolCall.id())
                        .setIndex(toolCall.index())
                        .setType(convertToolType(toolCall.type()));

        if (toolCall.function() != null) {
            builder.setFunction(
                    GrpcChatCompletionResponse.GrpcToolCall.GrpcFunctionCall.newBuilder()
                            .setName(toolCall.function().name() == null ? "" : toolCall.function().name())
                            .setArguments(toolCall.function().arguments() == null ? "" : toolCall.function().arguments().toString())
                            .build()
            );
        }

        return builder.build();
    }

    private GrpcChatCompletionResponse.GrpcRole convertRole(Role role) {
        switch (role) {
            case SYSTEM:
                return GrpcChatCompletionResponse.GrpcRole.SYSTEM;
            case USER:
                return GrpcChatCompletionResponse.GrpcRole.USER;
            case ASSISTANT:
                return GrpcChatCompletionResponse.GrpcRole.ASSISTANT;
            case TOOL:
                return GrpcChatCompletionResponse.GrpcRole.TOOL;
            case FUNCTION:
                return GrpcChatCompletionResponse.GrpcRole.FUNCTION;
            default:
                return GrpcChatCompletionResponse.GrpcRole.UNKNOWN;
        }
    }

    private GrpcChatCompletionResponse.GrpcToolCall.GrpcToolType convertToolType(ToolType type) {
        if (Objects.isNull(type)) {
            return GrpcChatCompletionResponse.GrpcToolCall.GrpcToolType.UNKNOWN;
        }
        switch (type) {
            case FUNCTION:
                return GrpcChatCompletionResponse.GrpcToolCall.GrpcToolType.FUNCTION;
            default:
                return GrpcChatCompletionResponse.GrpcToolCall.GrpcToolType.UNKNOWN;
        }
    }

    private ChatCompletionRequest convertToChatCompletionRequest(GrpcChatRequest request) {
        ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                .model(Config.model)
                .addUserMessage(request.getQuery())
                .addSystemMessage("You are a device maintenance and repair assistant. When the user asks" +
                        " about device maintenance or repair, call the get_knowledge_from_es function to retrieve " +
                        "relevant information from Elasticsearch. If the function is called, respond based on the" +
                        " function's return results; if the function is not called, respond directly" +
                        " If the response references knowledge from the knowledge base and sourceUrl has a" +
                        " value, add a footnote marker (e.g., [1]) in the text and append the footnote content " +
                        "in the format `[fileName](sourceUrl)` at the end of the response. If there is no sourceUrl," +
                        " do not add any footnotes. Always provide accurate and helpful responses");

        //需要查询es 拿到数据，并添加到消息中
        GrpcChatRequest.GrpcToolCallVo toolCallVo = request.getToolCallVo();
        if (StringUtils.isNotBlank(toolCallVo.getToolCallId())) {
            String functionName = toolCallVo.getFunctionName();
            String params = toolCallVo.getToolArguments();
            builder.addToolMessage(toolCallVo.getToolCallId(), toolCallVo.getToolCallResult());
            //查询es，合并消息
        } else {
            builder.tools(WEATHER_TOOL);
        }
        return builder.build();
    }
}
