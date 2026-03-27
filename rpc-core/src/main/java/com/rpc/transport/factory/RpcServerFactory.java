package com.rpc.transport.factory;

import com.rpc.registry.ServiceRegistry;
import com.rpc.transport.RpcServer;
import com.rpc.transport.TransportType;
import com.rpc.transport.netty.server.RpcNettyServer;
import com.rpc.transport.netty.server.config.RpcServerConfig;
import com.rpc.transport.socket.server.RpcSocketServer;

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
