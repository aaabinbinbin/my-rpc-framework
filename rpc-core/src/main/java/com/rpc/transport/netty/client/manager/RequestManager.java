package com.rpc.transport.netty.client.manager;

import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求管理器
 * 管理所有发出但未收到响应的请求
 */
@Slf4j
public class RequestManager {
    /**
     * 存储待处理的请求
     * key: requestId
     * value: CompletableFuture（用于接收响应）
     */
    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 添加新的请求
     * @param requestId 请求 ID
     * @return CompletableFuture
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        log.debug("添加请求：requestId={}", requestId);
        return future;
    }

    /**
     * 收到响应，完成 Future
     * @param response RPC 响应
     */
    public void completeResponse(RpcResponse response) {
        long requestId = Long.parseLong(response.getRequestId());
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(response);
            log.debug("完成请求：requestId={}, code={}", requestId, response.getCode());
        } else {
            log.warn("未找到对应的请求：requestId={}", requestId);
        }
    }

    /**
     * 请求失败，异常完成 Future
     * @param requestId 请求 ID
     * @param cause 异常原因
     */
    public void failRequest(long requestId, Throwable cause) {
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.completeExceptionally(cause);
            log.error("请求失败：requestId={}", requestId, cause);
        }
    }

    /**
     * 清理超时的请求
     * @param timeoutMs 超时时间（毫秒）
     */
    public void clearTimeoutRequests(long timeoutMs) {
        // TODO: 可以添加超时检查逻辑
        // 实际项目中可以使用定时任务或时间轮算法
    }

    /**
     * 获取待处理请求数量
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }
}
