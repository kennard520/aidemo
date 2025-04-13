package org.ai.aidemo;

import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.ai.aidemo.proto.ChatServiceGrpc;
import org.ai.aidemo.proto.GrpcChatCompletionResponse;
import org.ai.aidemo.proto.GrpcChatRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ChatGrpcClient {

    @GrpcClient("chat-service") // matches your service name in config
    private ChatServiceGrpc.ChatServiceStub chatServiceStub;

    public Flux<GrpcChatCompletionResponse> chatStream(GrpcChatRequest request) {
        return Flux.create(emitter -> {
                    StreamObserver<GrpcChatCompletionResponse> responseObserver = new StreamObserver<>() {
                        @Override
                        public void onNext(GrpcChatCompletionResponse response) {
                            emitter.next(response);
                        }

                        @Override
                        public void onError(Throwable t) {
                            emitter.error(t);
                        }

                        @Override
                        public void onCompleted() {
                            emitter.complete();
                            log.info("gRPC stream completed");
                        }
                    };
                    // 2. 启动gRPC调用（设置30秒超时）
                    chatServiceStub
                            .withDeadlineAfter(60, TimeUnit.SECONDS)
                            .chatStream(request, responseObserver);

                    // 3. 处理取消请求
                    emitter.onDispose(() -> {
                        if (responseObserver instanceof ClientCallStreamObserver) {
                            ((ClientCallStreamObserver<?>) responseObserver).cancel("Client disconnected", null);
                        }
                        log.warn("gRPC stream cancelled by client");
                    });

                    // Start the call
                    chatServiceStub.chatStream(request, responseObserver);

                }, FluxSink.OverflowStrategy.BUFFER);
    }
}