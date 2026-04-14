package com.rpc.core.transport.netty.client.request;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.common.exception.dedicated.ClientOverloadedException;
import com.rpc.core.observability.metrics.ClientRuntimeMetricsManager;
import com.rpc.core.protocol.message.RpcResponse;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端 pending 请求管理器。
 *
 * 所处阶段：请求已经写入或即将写入 Netty Channel，客户端需要等待异步响应。
 * 主要职责：
 * - 维护 requestId 到 CompletableFuture 的映射。
 * - 维护 Channel 到 requestId 的反向索引，便于连接断开时批量失败请求。
 * - 通过 maxPendingRequests 限制客户端内存和等待队列规模。
 * - 定期清理超时请求，避免 pending 表泄漏。
 *
 * 注意事项：
 * - addRequest 成功后，任何完成、失败、超时、连接关闭路径都必须释放 pending 计数。
 * - requestId 必须全局唯一到足以区分当前未完成请求，否则响应会匹配错误。
 */
@Slf4j
public class RequestManager {
    private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Channel, Set<Long>> channelRequests = new ConcurrentHashMap<>();
    private final int maxPendingRequests;
    private final AtomicInteger pendingCount = new AtomicInteger(0);

    public RequestManager() {
        this(Integer.MAX_VALUE);
    }

    public RequestManager(int maxPendingRequests) {
        this.maxPendingRequests = Math.max(1, maxPendingRequests);
    }

    /**
     * 注册一个等待响应的请求。
     *
     * 边界处理：
     * - pending 超过预算时快速失败为 CLIENT_BUSY。
     * - requestId 重复时回滚已占用的 pending 名额。
     * - 同时建立 channel 反向索引，连接关闭时可批量失败该连接上的请求。
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId, Channel channel, long timeoutMillis) {
        if (!tryAcquirePendingSlot()) {
            ClientRuntimeMetricsManager.getInstance().getMetrics().recordPendingLimitRejection();
            throw new ClientOverloadedException(
                    ClientOverloadedException.Reason.PENDING_REQUEST_LIMIT_EXCEEDED,
                    "Too many pending RPC requests"
            );
        }
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        long safeTimeoutMillis = Math.max(1L, timeoutMillis);
        PendingRequest pendingRequest = new PendingRequest(
                future,
                channel,
                System.currentTimeMillis() + safeTimeoutMillis
        );
        PendingRequest previous = pendingRequests.putIfAbsent(requestId, pendingRequest);
        if (previous != null) {
            pendingCount.decrementAndGet();
            throw new IllegalStateException("Duplicate RPC requestId: " + requestId);
        }
        channelRequests.compute(channel, (currentChannel, requestIds) -> {
            Set<Long> updated = requestIds == null ? ConcurrentHashMap.newKeySet() : requestIds;
            updated.add(requestId);
            return updated;
        });
        log.debug("Added pending request requestId={}, channel={}, timeout={}ms",
                requestId, channel.id(), safeTimeoutMillis);
        return future;
    }

    /** 收到服务端响应后按 requestId 完成对应 future；找不到通常说明请求已超时或连接已失败。 */
    public void completeResponse(RpcResponse response) {
        long requestId = Long.parseLong(response.getRequestId());
        PendingRequest pendingRequest = removePendingRequest(requestId);
        if (pendingRequest != null) {
            pendingRequest.future().complete(response);
            log.debug("Completed pending request requestId={}, code={}", requestId, response.getCode());
        } else {
            log.warn("No pending request found for requestId={}", requestId);
        }
    }

    /** 主动失败某个请求，通常发生在发送异常、超时或客户端资源不足时。 */
    public void failRequest(long requestId, Throwable cause) {
        PendingRequest pendingRequest = removePendingRequest(requestId);
        if (pendingRequest != null) {
            pendingRequest.future().completeExceptionally(cause);
            if (cause instanceof TimeoutException) {
                log.warn("Pending request timed out requestId={}: {}", requestId, cause.getMessage());
            } else if (cause instanceof RpcException rpcException
                    && rpcException.getErrorCode() == ErrorCode.CLIENT_BUSY) {
                log.warn("Pending request rejected by client budget requestId={}: {}", requestId, cause.getMessage());
            } else if (cause instanceof IllegalStateException) {
                log.warn("Pending request failed requestId={}: {}", requestId, cause.getMessage());
                log.debug("Pending request failure details requestId={}", requestId, cause);
            } else {
                log.error("Pending request failed requestId={}", requestId, cause);
            }
        }
    }

    /** 取消并移除某个请求，适用于调用方主动放弃等待的场景。 */
    public void removeRequest(long requestId) {
        PendingRequest pendingRequest = removePendingRequest(requestId);
        if (pendingRequest != null) {
            pendingRequest.future().cancel(false);
            log.warn("Removed pending request requestId={}", requestId);
        }
    }

    /** 扫描并失败所有已经超过 deadline 的请求，避免 pending 表持续增长。 */
    public void clearTimeoutRequests(long nowMillis) {
        int timedOut = 0;
        for (Map.Entry<Long, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest pendingRequest = entry.getValue();
            if (pendingRequest.deadlineMillis() > nowMillis) {
                continue;
            }
            if (pendingRequests.remove(entry.getKey(), pendingRequest)) {
                pendingCount.decrementAndGet();
                unregisterChannelRequest(pendingRequest.channel(), entry.getKey());
                pendingRequest.future().completeExceptionally(
                        new TimeoutException("RPC request timed out before response arrived")
                );
                timedOut++;
            }
        }
        if (timedOut > 0) {
            ClientRuntimeMetricsManager.getInstance().getMetrics().recordTimeoutCleared(timedOut);
            log.warn("Cleared timed out pending requests, count={}", timedOut);
        }
    }

    /** 客户端关闭时失败所有未完成请求，让调用方立即感知关闭而不是继续等待超时。 */
    public void failAll(Throwable cause) {
        int count = pendingRequests.size();
        pendingRequests.forEach((requestId, pendingRequest) -> {
            if (pendingRequests.remove(requestId, pendingRequest)) {
                pendingCount.decrementAndGet();
                unregisterChannelRequest(pendingRequest.channel(), requestId);
                pendingRequest.future().completeExceptionally(cause);
            }
        });
        channelRequests.clear();
        log.warn("Failed all pending requests, count={}", count);
    }

    /** 连接断开时失败该 Channel 上的所有 pending 请求，避免等待无意义的超时。 */
    public void failRequestsForChannel(Channel channel, Throwable cause) {
        Set<Long> requestIds = channelRequests.remove(channel);
        if (requestIds == null || requestIds.isEmpty()) {
            return;
        }
        int failed = 0;
        for (Long requestId : new HashSet<>(requestIds)) {
            PendingRequest pendingRequest = pendingRequests.remove(requestId);
            if (pendingRequest != null) {
                pendingCount.decrementAndGet();
                pendingRequest.future().completeExceptionally(cause);
                failed++;
            }
        }
        log.warn("Failed pending requests for channel={}, count={}", channel.id(), failed);
    }

    public int getPendingCount() {
        return pendingCount.get();
    }

    /** 从 pending 表和 channel 反向索引中同时移除请求，并释放 pending 计数。 */
    private PendingRequest removePendingRequest(long requestId) {
        PendingRequest pendingRequest = pendingRequests.remove(requestId);
        if (pendingRequest != null) {
            pendingCount.decrementAndGet();
            unregisterChannelRequest(pendingRequest.channel(), requestId);
        }
        return pendingRequest;
    }

    /** 尝试占用一个客户端 pending 名额；这是客户端背压的第一道硬边界。 */
    private boolean tryAcquirePendingSlot() {
        while (true) {
            int current = pendingCount.get();
            if (current >= maxPendingRequests) {
                return false;
            }
            if (pendingCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void unregisterChannelRequest(Channel channel, long requestId) {
        channelRequests.computeIfPresent(channel, (currentChannel, requestIds) -> {
            requestIds.remove(requestId);
            return requestIds.isEmpty() ? null : requestIds;
        });
    }

    private record PendingRequest(
            CompletableFuture<RpcResponse> future,
            Channel channel,
            long deadlineMillis
    ) {
    }
}
