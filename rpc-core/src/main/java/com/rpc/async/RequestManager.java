package com.rpc.async;

import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 请求管理器（增强版）
 * 管理所有发出但未收到响应的请求
 *
 * 新增功能：
 * - 支持超时自动清理
 * - 支持 AsyncRpcResult
 * - 定时任务清理超时请求
 */
@Slf4j
public class RequestManager {
    /**
     * 存储待处理的请求
     * key: requestId
     * value: CompletableFuture（用于接收响应）
     */
    private static final Map<Long, CompletableFuture<RpcResponse>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 存储带超时的请求（用于清理）
     * key: requestId
     * value: 过期时间戳
     */
    private static final Map<Long, Long> requestTimeouts = new ConcurrentHashMap<>();

    /** 定时任务执行器（用于清理超时请求） */
    private static final ScheduledExecutorService SCHEDULER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "RequestManager-Timeout-Cleaner");
                t.setDaemon(true);
                return t;
            });

    /** 默认超时时间（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 30000; // 30 秒

    // 启动定时清理任务
    static {
        SCHEDULER.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            requestTimeouts.entrySet().removeIf(entry -> {
                if (now > entry.getValue()) {
                    Long requestId = entry.getKey();
                    CompletableFuture<RpcResponse> future =
                            pendingRequests.remove(requestId);
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(
                                new java.util.concurrent.TimeoutException(
                                        "请求超时：requestId=" + requestId));
                        log.warn("清理超时请求：requestId={}", requestId);
                    }
                    return true; // 移除
                }
                return false; // 保留
            });
        }, 10, 5, TimeUnit.SECONDS); // 每 5 秒清理一次
    }

    /**
     * 添加新的请求（默认超时）
     * @param requestId 请求 ID
     * @return CompletableFuture
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        return addRequest(requestId, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 添加新的请求（自定义超时）
     * @param requestId 请求 ID
     * @param timeoutMs 超时时间（毫秒）
     * @return CompletableFuture
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId, long timeoutMs) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        requestTimeouts.put(requestId, System.currentTimeMillis() + timeoutMs);
        log.debug("添加请求：requestId={}, timeout={}ms", requestId, timeoutMs);
        return future;
    }

    /**
     * 添加异步结果请求
     * @param requestId 请求 ID
     * @param asyncResult 异步结果封装
     * @return AsyncRpcResult
     */
    public <T> AsyncRpcResult<T> addAsyncRequest(long requestId, AsyncRpcResult<T> asyncResult) {
        pendingRequests.put(requestId, asyncResult.getResponseFuture());
        requestTimeouts.put(requestId, System.currentTimeMillis() + DEFAULT_TIMEOUT_MS);
        log.debug("添加异步请求：requestId={}", requestId);
        return asyncResult;
    }

    /**
     * 添加异步结果请求（自定义超时）
     */
    public <T> AsyncRpcResult<T> addAsyncRequest(long requestId, AsyncRpcResult<T> asyncResult,
                                                 long timeoutMs) {
        pendingRequests.put(requestId, asyncResult.getResponseFuture());
        requestTimeouts.put(requestId, System.currentTimeMillis() + timeoutMs);
        log.debug("添加异步请求：requestId={}, timeout={}ms", requestId, timeoutMs);
        return asyncResult;
    }

    /**
     * 收到响应，完成 Future
     * @param response RPC 响应
     */
    public void completeResponse(RpcResponse response) {
        long requestId = Long.parseLong(response.getRequestId());
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        requestTimeouts.remove(requestId);

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
        requestTimeouts.remove(requestId);

        if (future != null) {
            future.completeExceptionally(cause);
            log.error("请求失败：requestId={}", requestId, cause);
        }
    }

    /**
     * 清理超时的请求（手动调用，作为定时的补充）
     * 注：主要由定时任务自动执行，此方法供特殊场景使用
     */
    public void clearTimeoutRequests() {
        long now = System.currentTimeMillis();
        requestTimeouts.entrySet().removeIf(entry -> {
            if (now > entry.getValue()) {
                Long requestId = entry.getKey();
                failRequest(requestId, new java.util.concurrent.TimeoutException(
                        "请求超时：" + requestId));
                return true;
            }
            return false;
        });
    }

    /**
     * 获取待处理请求数量
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }

    /**
     * 关闭管理器（清理所有请求）
     */
    public void shutdown() {
        log.info("关闭请求管理器，清理{}个待处理请求", pendingRequests.size());

        // 取消所有待处理请求
        pendingRequests.forEach((requestId, future) -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });

        pendingRequests.clear();
        requestTimeouts.clear();

        // 关闭定时任务
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
