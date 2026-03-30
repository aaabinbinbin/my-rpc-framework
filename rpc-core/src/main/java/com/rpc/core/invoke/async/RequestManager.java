package com.rpc.core.invoke.async;

import com.rpc.core.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 供高层异步调用 API（应用接口）使用的异步请求管理器。
 */
@Slf4j
public class RequestManager {
    private static final Map<Long, CompletableFuture<RpcResponse>> PENDING_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<Long, Long> REQUEST_TIMEOUTS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService SCHEDULER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "RequestManager-Timeout-Cleaner");
                thread.setDaemon(true);
                return thread;
            });
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    static {
        SCHEDULER.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            REQUEST_TIMEOUTS.entrySet().removeIf(entry -> {
                if (now <= entry.getValue()) {
                    return false;
                }
                Long requestId = entry.getKey();
                CompletableFuture<RpcResponse> future = PENDING_REQUESTS.remove(requestId);
                if (future != null && !future.isDone()) {
                    future.completeExceptionally(new TimeoutException("Request timed out, requestId=" + requestId));
                    log.warn("Async request timed out, requestId={}", requestId);
                }
                return true;
            });
        }, 10, 5, TimeUnit.SECONDS);
    }

    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        return addRequest(requestId, DEFAULT_TIMEOUT_MS);
    }

    public CompletableFuture<RpcResponse> addRequest(long requestId, long timeoutMs) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        PENDING_REQUESTS.put(requestId, future);
        REQUEST_TIMEOUTS.put(requestId, System.currentTimeMillis() + timeoutMs);
        log.debug("Added async request, requestId={}, timeout={}ms", requestId, timeoutMs);
        return future;
    }

    public <T> AsyncRpcResult<T> addAsyncRequest(long requestId, AsyncRpcResult<T> asyncResult) {
        return addAsyncRequest(requestId, asyncResult, DEFAULT_TIMEOUT_MS);
    }

    public <T> AsyncRpcResult<T> addAsyncRequest(long requestId, AsyncRpcResult<T> asyncResult, long timeoutMs) {
        PENDING_REQUESTS.put(requestId, asyncResult.getResponseFuture());
        REQUEST_TIMEOUTS.put(requestId, System.currentTimeMillis() + timeoutMs);
        log.debug("Added async rpc result, requestId={}, timeout={}ms", requestId, timeoutMs);
        return asyncResult;
    }

    public void completeResponse(RpcResponse response) {
        long requestId = Long.parseLong(response.getRequestId());
        CompletableFuture<RpcResponse> future = PENDING_REQUESTS.remove(requestId);
        REQUEST_TIMEOUTS.remove(requestId);
        if (future != null) {
            future.complete(response);
            log.debug("Completed async request, requestId={}, code={}", requestId, response.getCode());
        } else {
            log.warn("No pending async request found for requestId={}", requestId);
        }
    }

    public void failRequest(long requestId, Throwable cause) {
        CompletableFuture<RpcResponse> future = PENDING_REQUESTS.remove(requestId);
        REQUEST_TIMEOUTS.remove(requestId);
        if (future != null) {
            future.completeExceptionally(cause);
            log.error("Async request failed, requestId={}", requestId, cause);
        }
    }

    public void clearTimeoutRequests() {
        long now = System.currentTimeMillis();
        REQUEST_TIMEOUTS.entrySet().removeIf(entry -> {
            if (now <= entry.getValue()) {
                return false;
            }
            Long requestId = entry.getKey();
            failRequest(requestId, new TimeoutException("Request timed out, requestId=" + requestId));
            return true;
        });
    }

    public int getPendingCount() {
        return PENDING_REQUESTS.size();
    }

    public void shutdown() {
        log.info("Shutting down async request manager, pending={}", PENDING_REQUESTS.size());

        PENDING_REQUESTS.forEach((requestId, future) -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        PENDING_REQUESTS.clear();
        REQUEST_TIMEOUTS.clear();

        SCHEDULER.shutdown();
        try {
            if (!SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
