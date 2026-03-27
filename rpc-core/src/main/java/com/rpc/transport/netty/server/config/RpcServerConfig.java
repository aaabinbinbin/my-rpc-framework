package com.rpc.transport.netty.server.config;

import com.rpc.transport.TransportType;
import lombok.Data;

/**
 * RPC server config.
 */
@Data
public class RpcServerConfig {
    private TransportType transportType = TransportType.NETTY;

    private String host;

    private int port = 8080;

    private int bossThreads = 1;

    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;

    private byte serializerType = 1;

    private int shutdownTimeout = 10;

    private int readerIdleTime = 30000;

    private int writerIdleTime = 0;

    private int allIdleTime = 0;

    public static RpcServerConfig custom() {
        return new RpcServerConfig();
    }

    public RpcServerConfig transportType(TransportType transportType) {
        this.transportType = transportType;
        return this;
    }

    public RpcServerConfig host(String host) {
        this.host = host;
        return this;
    }

    public RpcServerConfig port(int port) {
        this.port = port;
        return this;
    }

    public RpcServerConfig bossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
        return this;
    }

    public RpcServerConfig workerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public RpcServerConfig serializerType(byte serializerType) {
        this.serializerType = serializerType;
        return this;
    }

    public RpcServerConfig shutdownTimeout(int shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
        return this;
    }

    public RpcServerConfig readerIdleTime(int readerIdleTime) {
        this.readerIdleTime = readerIdleTime;
        return this;
    }

    public RpcServerConfig writerIdleTime(int writerIdleTime) {
        this.writerIdleTime = writerIdleTime;
        return this;
    }

    public RpcServerConfig allIdleTime(int allIdleTime) {
        this.allIdleTime = allIdleTime;
        return this;
    }
}
