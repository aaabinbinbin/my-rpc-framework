package com.rpc.transport.netty.client.handler;

import com.rpc.core.transport.netty.client.handler.RpcClientHandler;
import com.rpc.core.transport.netty.client.request.RequestManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC客户端处理器测试")
class RpcClientHandlerTest {
    @DisplayName("验证失败待处理请求当通道变为失效场景")
    @Test
    void shouldFailPendingRequestsWhenChannelBecomesInactive() {
        RequestManager requestManager = new RequestManager();
        EmbeddedChannel firstChannel = new EmbeddedChannel(new RpcClientHandler(requestManager));
        EmbeddedChannel secondChannel = new EmbeddedChannel(new RpcClientHandler(requestManager));
        CompletableFuture<?> firstFuture = requestManager.addRequest(1L, firstChannel, 1_000L);
        CompletableFuture<?> secondFuture = requestManager.addRequest(2L, secondChannel, 1_000L);

        firstChannel.pipeline().fireChannelInactive();

        assertTrue(firstFuture.isCompletedExceptionally());
        assertTrue(!secondFuture.isDone());
        assertEquals(1, requestManager.getPendingCount());

        firstChannel.finishAndReleaseAll();
        secondChannel.finishAndReleaseAll();
    }

    @DisplayName("验证失败待处理请求On异常Caught场景")
    @Test
    void shouldFailPendingRequestsOnExceptionCaught() {
        RequestManager requestManager = new RequestManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RpcClientHandler(requestManager));
        CompletableFuture<?> future = requestManager.addRequest(3L, channel, 1_000L);
        IllegalStateException cause = new IllegalStateException("boom");

        channel.pipeline().fireExceptionCaught(cause);

        assertTrue(future.isCompletedExceptionally());
        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        assertSame(cause, thrown.getCause());
        assertEquals(0, requestManager.getPendingCount());

        channel.finishAndReleaseAll();
    }
}
