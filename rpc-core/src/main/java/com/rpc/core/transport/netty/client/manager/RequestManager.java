package com.rpc.core.transport.netty.client.manager;

import com.rpc.core.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 Netty 客户端仍在等待中的请求。
 */
@Slf4j
public class RequestManager {
    // requestId -> future 是客户端“请求发出”和“响应回收”之间的桥。
    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests = new ConcurrentHashMap<>();

    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        log.debug("Added pending request requestId={}", requestId);
        return future;
    }

    public void completeResponse(RpcResponse response) {
        long requestId = Long.parseLong(response.getRequestId());
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(response);
            log.debug("Completed pending request requestId={}, code={}", requestId, response.getCode());
        } else {
            // 常见于请求已超时或已被上层取消，此时响应只能被丢弃。
            log.warn("No pending request found for requestId={}", requestId);
        }
    }

    public void failRequest(long requestId, Throwable cause) {
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.completeExceptionally(cause);
            log.error("Pending request failed requestId={}", requestId, cause);
        }
    }

    public void clearTimeoutRequests(long timeoutMs) {
        // 当前超时控制交给 future.get(timeout)。
        // 这里保留方法，是为了后续如果要做主动超时清理时，不需要再改对外接口。
    }

    public int getPendingCount() {
        return pendingRequests.size();
    }
}
