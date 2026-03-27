package com.rpc.transport.factory;

import com.rpc.config.RpcClientConfig;
import com.rpc.registry.ServiceRegistry;
import com.rpc.transport.RpcTransport;
import com.rpc.transport.TransportType;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.transport.socket.client.RpcSocketClient;

public final class RpcTransportFactory {
    private RpcTransportFactory() {
    }

    public static RpcTransport create(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        TransportType transportType = config.getTransportType();
        if (transportType == null) {
            transportType = TransportType.NETTY;
        }

        switch (transportType) {
            case SOCKET:
                return new RpcSocketClient(config, serviceRegistry);
            case NETTY:
            default:
                return new RpcNettyClient(config, serviceRegistry);
        }
    }
}
