package com.rpc.config;

import lombok.Data;

/**
 * RPC 服务端配置
 */
@Data
public class RpcServerConfig {
    /** 服务器端口 */
    private int port = 8080;

    /** Boss 线程数 */
    private int bossThreads = 1;

    /** Worker 线程数 */
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;

    /** 序列化器类型 */
    private byte serializerType = 1;  // 默认 Kryo

    /** 优雅关闭超时时间（秒） */
    private int shutdownTimeout = 10;

    // Builder 模式
    public static RpcServerConfig custom() {
        return new RpcServerConfig();
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
}
