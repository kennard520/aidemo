package org.ai.aidemo.controller;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.Json;
import io.github.pigmesh.ai.deepseek.core.chat.*;
import lombok.extern.slf4j.Slf4j;
import org.ai.aidemo.ChatGrpcClient;
import org.ai.aidemo.proto.GrpcChatCompletionResponse;
import org.ai.aidemo.proto.GrpcChatRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

@RestController
@Slf4j
public class AiController {

    private static DeepSeekClient deepSeekClient;
    private static String model = "deepseek/deepseek-v3";

    static {
        deepSeekClient = DeepSeekClient.builder().baseUrl("https://api.ppinfra.com/v3/openai").openAiApiKey("sk_nH_pAiFv3wR3bu2iCaqDb5-bbNnXhkYsxvwRrhY2hRc").model(model).build();
    }

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

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatCompletionResponse> chat(String prompt) {
        return deepSeekClient.chatFluxCompletion(prompt);
    }

    public final static HashMap<String, String> cache = new HashMap<>();
    Function<List<ChatCompletionChoice>, String> choicesProcess = list -> list.stream().map(e -> e.delta().content())
            .collect(Collectors.joining());

    Function<String, String> elt = s -> s.replaceAll("<think>[\\s\\S]*?</think>", "").replaceAll("\n", "");

    @Autowired
    private WebClient webClient;

//    @GetMapping(value = "/chat/advanced", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<ChatCompletionResponse> chatAdvanced(String prompt, String sessionId) {
//        RequestVo requestVo = new RequestVo();
//        requestVo.setQuery(prompt);
//        requestVo.setSessionId(sessionId);
//        StringBuffer sb = new StringBuffer();
//        AtomicBoolean isToolCallInProgress = new AtomicBoolean(false);
//        webClient.post().bodyValue(requestVo)
//                .accept(MediaType.TEXT_EVENT_STREAM)
//                .retrieve()
//                .bodyToFlux(ChatCompletionResponse.class)
//                .filter(r -> {
//                    List<ChatCompletionChoice> choices = r.choices();
//                    if (choices.isEmpty()) {
//                        return false;
//                    }
//                    ChatCompletionChoice chatCompletionChoice = choices.get(0);
//                    Delta delta = chatCompletionChoice.delta();
//                    if (Objects.isNull(delta)) return false;
//                    String content = delta.content();
//                    List<ToolCall> toolCalls = delta.toolCalls();
//                    if (!StringUtils.isNotBlank(content) && (Objects.isNull(toolCalls) || toolCalls.isEmpty()))
//                        return false;
//                    return true;
//                }).concatMap(response -> {
//                    log.info("response {}", response);
//                    Delta delta = response.choices().get(0).delta();
//                    // 情况2：工具调用进行中（忽略所有中间文本）
//                    if (isToolCallInProgress.get()) {
//                        List<ToolCall> toolCalls = delta.toolCalls();
//                        if (!CollectionUtils.isEmpty(toolCalls)) {
//                            String arguments = toolCalls.get(0).function().arguments();
//                            sb.append(arguments);
//                        }
//                        return Flux.empty();
//                    }
//                    // 情况1：检测到工具调用开始
//                    if (delta.toolCalls() != null) {
//                        isToolCallInProgress.set(true);
//                        return Flux.empty();
//                    }
//                    // 情况3：正常返回最终结果
//                    return Flux.just(response);
//
//                }).doFinally(r -> log.info("arguments {}", sb.toString()))
//                .doOnError(e -> log.error("/chat/advanced error:{}", e.getMessage()));
//    }

    @GetMapping(value = "/chat/advanced", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatCompletionResponse> chatAdvanced(String prompt, String cacheCode) {

        ChatCompletionRequest request = ChatCompletionRequest.builder().model(model)
                .messages(UserMessage.from(prompt), SystemMessage.from("You are a device maintenance and repair assistant. When the user asks about device maintenance or repair, call the get_knowledge_from_es function to retrieve relevant information from Elasticsearch. If the function is called, respond based on the function's return results; if the function is not called, respond directly. If the response references knowledge from the knowledge base and sourceUrl has a value, add a footnote marker (e.g., [1]) in the text and append the footnote content in the format `[fileName](sourceUrl)` at the end of the response. If there is no sourceUrl, do not add any footnotes. Always provide accurate and helpful responses"))
                .tools(WEATHER_TOOL).build();
        // 只保留上一次回答内容
        cache.remove(cacheCode);
        StringBuffer sb = new StringBuffer();
        AtomicBoolean isToolCallInProgress = new AtomicBoolean(false);
        return deepSeekClient.chatFluxCompletion(request)
                .filter(r -> {
                    log.info("filter {}", r);
                    List<ChatCompletionChoice> choices = r.choices();
                    if (choices.isEmpty()) {
                        return false;
                    }
                    ChatCompletionChoice chatCompletionChoice = choices.get(0);
                    Delta delta = chatCompletionChoice.delta();
                    if (Objects.isNull(delta)) return false;
                    String content = delta.content();
                    List<ToolCall> toolCalls = delta.toolCalls();
                    if (StringUtils.isBlank(content) &&
                            CollectionUtils.isEmpty(toolCalls) &&
                            StringUtils.isBlank(chatCompletionChoice.finishReason()))
                        return false;
                    return true;
                })
                .concatMap(response -> {
                    log.info("response {}", response);
                    Delta delta = response.choices().get(0).delta();
                    // 情况2：工具调用进行中（忽略所有中间文本）
                    if (isToolCallInProgress.get()) {
                        List<ToolCall> toolCalls = delta.toolCalls();
                        if (!CollectionUtils.isEmpty(toolCalls)) {
                            String arguments = toolCalls.get(0).function().arguments();
                            sb.append(arguments);
                        }
                        return Flux.empty();
                    }

                    // 情况1：检测到工具调用开始
                    if (delta.toolCalls() != null) {
                        isToolCallInProgress.set(true);
                        return Flux.empty();
                    }
                    // 情况3：正常返回最终结果
                    return Flux.just(response);

                }).doFinally(r -> log.info("arguments {}", sb.toString()))
                .doOnError(e -> log.error("/chat/advanced error:{}", e.getMessage()));
    }


    @GetMapping(value = "/chatai")
    public ChatCompletionResponse chat(String prompt, String cacheCode) {
        log.info("cacheCode {}", cacheCode);

        ChatCompletionRequest request = ChatCompletionRequest.builder().model(model)
                .messages(SystemMessage.from("You are a professional assistant. If a user asks about weather, you need to call the function get_current_weather"), UserMessage.from(prompt))
                .tools(WEATHER_TOOL)
                .maxCompletionTokens(5000).build();
        log.info("request {}", Json.toJson(request));
        // 只保留上一次回答内容
        cache.remove(cacheCode);
        ChatCompletionResponse execute = deepSeekClient.chatCompletion(request).execute();
        log.info("ChatCompletionResponse {}", Json.toJson(execute));
        log.info("{}", execute.choices().get(0).delta().toolCalls().get(0));

        return execute;
    }

    @Autowired
    private ChatGrpcClient grpcClient;

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GrpcChatCompletionResponse> chatStream(String prompt) {
        RequestVo requestVo = new RequestVo();
        requestVo.setQuery(prompt);
        return streamChatWithTools(requestVo);
    }


    private GrpcChatRequest convertToChatRequest(RequestVo requestVo) {
        GrpcChatRequest.Builder builder = GrpcChatRequest.newBuilder().setQuery(requestVo.getQuery())
                .setSession(requestVo.getSessionId() == null ? "" : requestVo.getSessionId())
                .setIsToolCallInProgress(requestVo.isToolCallInProgress())
                .setIsToolCallFinished(requestVo.isToolCallFinished());
        RequestVo.ToolCallVo toolCallVo = requestVo.getToolCallVo();
        if (toolCallVo != null) {
            GrpcChatRequest.GrpcToolCallVo.Builder newBuilder = GrpcChatRequest.GrpcToolCallVo.newBuilder();
            newBuilder.setToolCallId(toolCallVo.getToolCallId() == null ? "" : toolCallVo.getToolCallId());
            newBuilder.setFunctionName(toolCallVo.getFunctionName() == null ? "" : toolCallVo.getFunctionName());
            if (Objects.nonNull(toolCallVo.getToolArguments())) {
                newBuilder.setToolArguments(toolCallVo.getToolArguments().toString());
            }
            if (Objects.nonNull(toolCallVo.getToolCallResult())) {
                newBuilder.setToolCallResult(toolCallVo.getToolCallResult().toString());
            }
            GrpcChatRequest.GrpcToolCallVo build = newBuilder.build();
            builder.setToolCallVo(build);
        }
        return builder.build();
    }

    public Flux<GrpcChatCompletionResponse> streamChatWithTools(RequestVo requestVo) {

        return grpcClient.chatStream(convertToChatRequest(requestVo))
                .doOnNext(r -> log.info("Before filter: {}", r))
                .filter(r -> {
                    List<GrpcChatCompletionResponse.GrpcChatCompletionChoice> choices = r.getChoicesList();
                    if (choices.isEmpty()) {
                        return false;
                    }
                    GrpcChatCompletionResponse.GrpcChatCompletionChoice chatCompletionChoice = choices.get(0);
                    GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcDelta delta = chatCompletionChoice.getDelta();
                    String content = delta.getContent();
                    List<GrpcChatCompletionResponse.GrpcToolCall> toolCallsList = delta.getToolCallsList();
                    if (StringUtils.isBlank(content) &&
                            CollectionUtils.isEmpty(toolCallsList) &&
                            StringUtils.isBlank(chatCompletionChoice.getFinishReason()))
                        return false;
                    return true;
                })
                .doOnNext(r -> log.info("After filter: {}", r))
                .concatMap(response -> {
                    log.info("concatMap1 response {}", response);
                    if (requestVo.isToolCallFinished()) return Flux.just(response);

                    GrpcChatCompletionResponse.GrpcChatCompletionChoice.GrpcDelta delta = response.getChoices(0).getDelta();

                    // 情况1：检测到工具调用开始
                    if (requestVo.isToolCallInProgress()) {
                        List<GrpcChatCompletionResponse.GrpcToolCall> toolCalls = delta.getToolCallsList();
                        if (!CollectionUtils.isEmpty(toolCalls)) {
                            String arguments = toolCalls.get(0).getFunction().getArguments();
                            requestVo.getToolCallVo().getToolArguments().append(arguments);
                        }
                        if (!StringUtils.isNotBlank(response.getChoices(0).getFinishReason())) {
                            return Flux.empty();
                        }
                        requestVo.setToolCallInProgress(false);
                        requestVo.setToolCallFinished(true);
                        // 2. 执行工具函数
                        String toolName = requestVo.getToolCallVo().getFunctionName();
                        return executeToolAndGetResult(toolName, requestVo.getToolCallVo().getToolArguments().toString())
                                .flatMapMany(toolResult -> {
                                    requestVo.getToolCallVo().setToolCallResult(toolResult);
                                    return streamChatWithTools(requestVo);
                                });
                    }

                    // 情况1：检测到工具调用开始
                    if (!CollectionUtils.isEmpty(delta.getToolCallsList())) {
                        GrpcChatCompletionResponse.GrpcToolCall toolCall = delta.getToolCalls(0);
                        requestVo.getToolCallVo().setFunctionName(toolCall.getFunction().getName());
                        requestVo.getToolCallVo().setToolCallId(toolCall.getId());
                        requestVo.getToolCallVo().getToolArguments().setLength(0); // 清空旧参数
                        requestVo.setToolCallInProgress(true);
                        return Flux.empty();
                    }
                    // 情况3：正常返回内容
                    return Flux.just(response);
                }).doOnComplete(() -> log.info("Stream completed")).timeout(Duration.ofSeconds(60))
                .doOnError(e -> log.error("Stream error: {}", e.getMessage()));
    }

    private Mono<String> executeToolAndGetResult(String toolName, String params) {
        // 实现你的ES查询逻辑
        return Mono.just("ES查询结果"); // 示例
    }

    // 构造携带工具结果的后续请求


    public WebClient deepSeekWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.siliconflow.cn/v1/chat/completions")
                .defaultHeader("Authorization", "Bearer sk-kwrdxarfftrlwetitvthsbydrmjzmmfpwvifpdpiyiqmnumq")
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024); // 16MB
                })
                .build();
    }

    @GetMapping(value = "/chat/stream-a", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatCompletionResponse> chatStreama(String prompt) {
        RequestVo requestVo = new RequestVo();
        requestVo.setQuery(prompt);
        return streamChatWithToolsa(requestVo);
    }

    public Flux<ChatCompletionResponse> streamChatWithToolsa(RequestVo requestVo) {

        return webClient.post()
                .bodyValue(requestVo)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(ChatCompletionResponse.class)
                .filter(r -> {
                    List<ChatCompletionChoice> choices = r.choices();
                    if (choices.isEmpty()) {
                        return false;
                    }
                    ChatCompletionChoice chatCompletionChoice = choices.get(0);
                    Delta delta = chatCompletionChoice.delta();
                    String content = delta.content();
                    List<ToolCall> toolCallsList = delta.toolCalls();
                    if (StringUtils.isBlank(content) &&
                            CollectionUtils.isEmpty(toolCallsList) &&
                            StringUtils.isBlank(chatCompletionChoice.finishReason()))
                        return false;
                    return true;
                }).concatMap(response -> {
                    log.info("concatMap1 response {}", response);
                    if (requestVo.isToolCallFinished()) return Flux.just(response);

                    Delta delta = response.choices().get(0).delta();

                    // 情况1：检测到工具调用开始
                    if (requestVo.isToolCallInProgress()) {
                        List<ToolCall> toolCalls = delta.toolCalls();
                        if (!CollectionUtils.isEmpty(toolCalls)) {
                            String arguments = toolCalls.get(0).function().arguments();
                            requestVo.getToolCallVo().getToolArguments().append(arguments);
                        }
                        if (!StringUtils.isNotBlank(response.choices().get(0).finishReason())) {
                            return Flux.empty();
                        }
                        requestVo.setToolCallInProgress(false);
                        requestVo.setToolCallFinished(true);
                        // 2. 执行工具函数
                        String toolName = requestVo.getToolCallVo().getFunctionName();
                        return executeToolAndGetResult(toolName, requestVo.getToolCallVo().getToolArguments().toString())
                                .flatMapMany(toolResult -> {
                                    requestVo.getToolCallVo().setToolCallResult(toolResult);
                                    return streamChatWithToolsa(requestVo);
                                });
                    }

                    // 情况1：检测到工具调用开始
                    if (!CollectionUtils.isEmpty(delta.toolCalls())) {
                        ToolCall toolCall = delta.toolCalls().get(0);
                        requestVo.getToolCallVo().setFunctionName(toolCall.function().name());
                        requestVo.getToolCallVo().setToolCallId(toolCall.id());
                        requestVo.getToolCallVo().getToolArguments().setLength(0); // 清空旧参数
                        requestVo.setToolCallInProgress(true);
                        return Flux.empty();
                    }
                    // 情况3：正常返回内容
                    return Flux.just(response);
                }).timeout(Duration.ofSeconds(60))
                .doOnError(e -> log.error("Stream error: {}", e.getMessage()));
    }


}
