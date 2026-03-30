package com.rpc.core.transport.factory;

import com.rpc.core.config.RpcClientConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.TransportType;
import com.rpc.core.transport.netty.client.RpcNettyClient;
import com.rpc.core.transport.socket.client.RpcSocketClient;

public final class RpcTransportFactory {
    private RpcTransportFactory() {
    }

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

