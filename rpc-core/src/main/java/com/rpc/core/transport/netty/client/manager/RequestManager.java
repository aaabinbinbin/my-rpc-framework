package com.rpc.core.transport.netty.client.manager;

import com.rpc.core.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端请求管理器。
 *
 * 这个类解决的是“请求发送出去”和“响应异步回来”之间的关联问题：
 * 发送请求时先登记 requestId -> future；
 * 响应回来后再根据 requestId 找到对应 future 并完成它。
 *
 * 因此它是同步 RPC 外观能够建立在异步网络模型之上的关键组件。
 */
@Slf4j
public class RequestManager {
    /** requestId -> future 映射，表示当前还在等待中的请求。 */
    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 登记一个新请求，并返回与之绑定的 future。
     *
     * sendRequest() 会在真正发请求前先调用这里，
     * 后续客户端 handler 收到响应后再负责补全这个 future。
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        log.debug("Added pending request requestId={}", requestId);
        return future;
    }

    /**
     * 用响应完成一个等待中的请求。
     *
     * 如果找不到对应 future，通常表示该请求已经超时或被上层取消，
     * 这时只能丢弃这条迟到响应。
     */
    public void completeResponse(RpcResponse response) {
        long requestId = Long.parseLong(response.getRequestId());
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(response);
            log.debug("Completed pending request requestId={}, code={}", requestId, response.getCode());
        } else {
            log.warn("No pending request found for requestId={}", requestId);
        }
    }

    /** 让一个等待中的请求以异常形式结束。 */
    public void failRequest(long requestId, Throwable cause) {
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.completeExceptionally(cause);
            log.error("Pending request failed requestId={}", requestId, cause);
        }
    }

    /**
     * 预留的超时清理接口。
     *
     * 当前实现主要依赖 future.get(timeout) 控制同步等待超时，
     * 这里保留方法是为了后续如需主动清理超时请求时不必改外部接口。
     */
    public void clearTimeoutRequests(long timeoutMs) {
    }

    /** 返回当前还未完成的请求数。 */
    public int getPendingCount() {
        return pendingRequests.size();
    }
}
