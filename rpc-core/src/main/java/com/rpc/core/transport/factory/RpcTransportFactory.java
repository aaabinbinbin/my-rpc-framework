package com.rpc.core.transport.factory;

import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.TransportType;
import com.rpc.core.transport.netty.client.RpcNettyClient;
import com.rpc.core.transport.socket.legacy.client.RpcSocketClient;

/**
 * consumer 侧传输客户端工厂。
 *
 * 所处阶段：RpcConsumerBootstrap 初始化客户端时。
 * 主要职责：根据配置创建 Netty 或 legacy Socket 传输实现。
 */
public final class RpcTransportFactory {
    /** 工厂类不允许实例化。 */
    private RpcTransportFactory() {
    }

    /**
     * 创建传输客户端。
     *
     * 边界处理：transportType 为空时默认使用 Netty；Socket 路径保留为 legacy 兼容实现，不作为高并发首选。
     */
    public static RpcTransport create(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        TransportType transportType = config.getTransportType();
        if (transportType == null) {
            transportType = TransportType.NETTY;
        }

        switch (transportType) {
            case SOCKET:
                return new RpcSocketClient(config, serviceDiscovery);
            case NETTY:
            default:
                return new RpcNettyClient(config, serviceDiscovery);
        }
    }
}

