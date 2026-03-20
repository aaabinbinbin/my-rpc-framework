package com.rpc.connection;

import io.netty.channel.Channel;
import lombok.Data;


/**
 * 连接封装
 */
@Data
public class RpcConnection {
    /** Netty Channel */
    private Channel channel;

    /** 服务器地址 */
    private String host;

    /** 服务器端口 */
    private int port;

    /** 最后使用时间 */
    private long lastUseTime;

    /** 是否可用 */
    private boolean available;

    public RpcConnection(Channel channel, String host, int port) {
        this.channel = channel;
        this.host = host;
        this.port = port;
        this.lastUseTime = System.currentTimeMillis();
        this.available = true;
    }

    /**
     * 更新最后使用时间
     */
    public void updateLastUseTime() {
        this.lastUseTime = System.currentTimeMillis();
    }

    /**
     * 检查连接是否有效
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }
}
