package com.rpc.core.transport.factory;

import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.TransportType;
import com.rpc.core.transport.netty.server.RpcNettyServer;
import com.rpc.core.transport.netty.server.config.RpcServerConfig;
import com.rpc.core.transport.socket.server.RpcSocketServer;

public final class RpcServerFactory {
    private RpcServerFactory() {
    }

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

