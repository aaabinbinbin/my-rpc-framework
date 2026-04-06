package com.rpc.core.transport.netty.client.connection;

import io.netty.channel.Channel;
import lombok.Data;

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

    public RpcConnection(Channel channel, String host, int port) {
        this.channel = channel;
        this.host = host;
        this.port = port;
        this.lastUseTime = System.currentTimeMillis();
        this.available = true;
    }

    /** 刷新最后使用时间，供连接池统计连接活跃度。 */
    public void updateLastUseTime() {
        this.lastUseTime = System.currentTimeMillis();
    }

    /** 当前连接是否仍然处于活跃状态。 */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }
}
