package com.rpc.async;

import com.rpc.protocol.RpcResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * 异步 RPC 功能测试
 */
public class AsyncRpcTest {

    @Test
    public void testAsyncRpcResult_Success() throws Exception {
        // 给定
        RequestManager requestManager = new RequestManager();
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);

        long requestId = 123L;
        requestManager.addAsyncRequest(requestId, asyncResult);

        // 模拟收到响应
        RpcResponse response = RpcResponse.success("Hello World", String.valueOf(requestId));
        requestManager.completeResponse(response);

        // 当
        String result = asyncResult.get();

        // 断言
        assertEquals("Hello World", result);
        assertTrue(asyncResult.isDone());
    }

    @Test
    public void testAsyncRpcResult_Failure() {
        // 给定
        RequestManager requestManager = new RequestManager();
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);

        long requestId = 456L;
        requestManager.addAsyncRequest(requestId, asyncResult);

        // 模拟异常
        RuntimeException exception = new RuntimeException("网络错误");
        requestManager.failRequest(requestId, exception);

        // 当 & 断言
        assertThrows(Exception.class, () -> asyncResult.get());
        assertTrue(asyncResult.isDone());
    }

    @Test
    public void testCallback_Success() throws Exception {
        // 给定
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean callbackCalled = new AtomicBoolean(false);

        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);

        RpcCallback<String> callback = new RpcCallback<String>() {
            @Override
            public void onSuccess(String result) {
                System.out.println("回调收到：" + result);
                callbackCalled.set(true);
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable cause) {
                fail("不应该失败");
            }
        };

        asyncResult.addCallback(callback);

        // 触发完成
        asyncResult.setResult(RpcResponse.success("Test Result", "1"));

        // 等待回调
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(callbackCalled.get());
    }

    @Test
    public void testCallback_Failure() throws Exception {
        // 给定
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean failureCalled = new AtomicBoolean(false);

        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);

        RpcCallback<String> callback = new RpcCallback<String>() {
            @Override
            public void onSuccess(String result) {
                fail("不应该成功");
            }

            @Override
            public void onFailure(Throwable cause) {
                System.out.println("回调失败：" + cause.getMessage());
                failureCalled.set(true);
                latch.countDown();
            }
        };

        asyncResult.addCallback(callback);

        // 触发异常
        asyncResult.setException(new RuntimeException("测试异常"));

        // 等待回调
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(failureCalled.get());
    }

    @Test
    public void testToCompletableFuture() throws Exception {
        // 给定
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);
        CompletableFuture<String> future = asyncResult.toCompletableFuture();

        // 当
        asyncResult.setResult(RpcResponse.success("Future Test", "1"));
        String result = future.get(3, TimeUnit.SECONDS);

        // 断言
        assertEquals("Future Test", result);
    }
}