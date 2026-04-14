package com.rpc.core.transport.factory;

import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.TransportType;
import com.rpc.core.transport.netty.server.RpcNettyServer;
import com.rpc.core.config.server.RpcServerConfig;
import com.rpc.core.transport.socket.legacy.server.RpcSocketServer;

/**
 * RPC 服务端工厂。
 *
 * provider 启动器不应该直接依赖某个具体服务端实现，
 * 而应该通过工厂按传输类型选择 Netty 或 Socket 等不同后端。
 */
public final class RpcServerFactory {
    private RpcServerFactory() {
    }

    /**
     * 根据配置创建具体 RpcServer。
     *
     * 当前主要支持：
     * 1. NETTY
     * 2. SOCKET
     */
    public static RpcServer create(RpcServerConfig config, ServiceRegistry serviceRegistry) {
        TransportType transportType = config.getTransportType();
        if (transportType == null) {
            transportType = TransportType.NETTY;
        }

        switch (transportType) {
            case SOCKET:
                return new RpcSocketServer(config, serviceRegistry);
            case NETTY:
            default:
                return new RpcNettyServer(config, serviceRegistry);
        }
    }
}
