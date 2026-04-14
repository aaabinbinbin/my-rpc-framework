package com.rpc.core.transport.netty.client.connection;

import io.netty.channel.Channel;
import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对 Netty Channel 的轻量封装。
 *
 * 除了持有底层 Channel 之外，
 * 还额外保存了地址元信息和最后使用时间，
 * 便于连接池后续扩展空闲回收、健康检查等能力。
 */
@Data
public class RpcConnection {
    /** 底层 Netty 通道。 */
    private Channel channel;
    /** 远端 host。 */
    private String host;
    /** 远端 port。 */
    private int port;
    /** 最后一次使用时间。 */
    private long lastUseTime;
    /** 逻辑可用标记。 */
    private boolean available;
    /** 当前连接上正在等待响应的请求数，用于连接级背压和最少连接选择。 */
    private final AtomicInteger inflightRequests = new AtomicInteger(0);
    /** 单连接最大在途请求数，避免一个 Channel 被无限压入请求。 */
    private final int maxInflightRequests;

    public RpcConnection(Channel channel, String host, int port, int maxInflightRequests) {
        this.channel = channel;
        this.host = host;
        this.port = port;
        this.lastUseTime = System.currentTimeMillis();
        this.available = true;
        this.maxInflightRequests = Math.max(1, maxInflightRequests);
    }

    /** 刷新最后使用时间，供连接池统计连接活跃度。 */
    public void updateLastUseTime() {
        this.lastUseTime = System.currentTimeMillis();
    }

    /** 当前连接是否仍然处于活跃状态。 */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    /**
     * 尝试占用单连接在途请求名额。
     *
     * 发送前必须先占位，发送失败、响应完成或调用异常后必须释放，
     * 否则连接会被误判为一直繁忙。
     */
    public boolean tryAcquireRequestSlot() {
        while (true) {
            int current = inflightRequests.get();
            if (current >= maxInflightRequests) {
                return false;
            }
            if (inflightRequests.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 释放一次在途请求名额；使用下限保护避免异常路径重复释放导致负数。 */
    public void releaseRequestSlot() {
        inflightRequests.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    public int getInflightRequestCount() {
        return inflightRequests.get();
    }

    /** 当前连接是否还有继续承载请求的容量。 */
    public boolean hasCapacity() {
        return getInflightRequestCount() < maxInflightRequests;
    }
}
