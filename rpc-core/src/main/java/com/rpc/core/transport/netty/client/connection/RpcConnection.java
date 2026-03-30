package com.rpc.core.transport.netty.client.connection;

import io.netty.channel.Channel;
import lombok.Data;

/**
 * 对 Netty channel 及其地址元数据的轻量封装。
 */
@Data
public class RpcConnection {
    private Channel channel;
    private String host;
    private int port;
    // 目前 lastUseTime 主要用于后续扩展空闲连接回收能力。
    private long lastUseTime;
    private boolean available;

    public RpcConnection(Channel channel, String host, int port) {
        this.channel = channel;
        this.host = host;
        this.port = port;
        this.lastUseTime = System.currentTimeMillis();
        this.available = true;
    }

    public void updateLastUseTime() {
        this.lastUseTime = System.currentTimeMillis();
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }
}
