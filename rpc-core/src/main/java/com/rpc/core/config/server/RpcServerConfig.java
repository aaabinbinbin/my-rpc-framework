package com.rpc.core.config.server;

import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.transport.TransportType;
import lombok.Data;

/**
 * RPC 服务端配置。
 *
 * 这是 provider 侧真正下沉到 Netty / Socket 服务端实现时使用的配置对象，
 * 比 RpcFrameworkConfig 更贴近“服务端如何监听和执行”的细节。
 */
@Data
public class RpcServerConfig {
    /** 服务端传输类型。 */
    private TransportType transportType = TransportType.NETTY;
    /** 对外暴露的 host。 */
    private String host;
    /** 对外暴露的端口。 */
    private int port = 8080;
    /** Netty boss 线程数。 */
    private int bossThreads = 1;
    /** Netty worker 线程数。 */
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
    /** 业务线程池核心线程数。 */
    private int bizCoreThreads = Runtime.getRuntime().availableProcessors();
    /** 业务线程池最大线程数。 */
    private int bizMaxThreads = Runtime.getRuntime().availableProcessors() * 2;
    /** 业务线程池队列容量。 */
    private int bizQueueCapacity = 1000;
    /** 默认序列化类型码。 */
    private byte serializerType = 1;
    /** 优雅停机超时时间。 */
    private int shutdownTimeout = 10;
    /** 服务端读空闲时间。 */
    private int readerIdleTime = 30000;
    /** 服务端写空闲时间。 */
    private int writerIdleTime = 0;
    /** 服务端全空闲时间。 */
    private int allIdleTime = 0;

    public static RpcServerConfig custom() {
        return new RpcServerConfig();
    }

    /**
     * 从全局框架配置转换成 provider 侧运行时配置。
     *
     * 这一步把用户视角的配置下沉到服务端真正需要的监听端口、线程池、
     * 空闲检测和优雅停机参数，避免 provider bootstrap 直接堆积字段拼装逻辑。
     */
    public static RpcServerConfig fromFrameworkConfig(RpcFrameworkConfig frameworkConfig) {
        return RpcServerConfig.custom()
                .transportType(frameworkConfig.getTransportType())
                .host(frameworkConfig.getServerHost())
                .port(frameworkConfig.getServerPort())
                .bossThreads(frameworkConfig.getBossThreads())
                .workerThreads(frameworkConfig.getWorkerThreads())
                .bizCoreThreads(frameworkConfig.getBizCoreThreads())
                .bizMaxThreads(frameworkConfig.getBizMaxThreads())
                .bizQueueCapacity(frameworkConfig.getBizQueueCapacity())
                .shutdownTimeout(frameworkConfig.getShutdownTimeout())
                .readerIdleTime(frameworkConfig.getServerReaderIdleTime())
                .writerIdleTime(frameworkConfig.getServerWriterIdleTime())
                .allIdleTime(frameworkConfig.getServerAllIdleTime());
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

    public RpcServerConfig bizCoreThreads(int bizCoreThreads) {
        this.bizCoreThreads = bizCoreThreads;
        return this;
    }

    public RpcServerConfig bizMaxThreads(int bizMaxThreads) {
        this.bizMaxThreads = bizMaxThreads;
        return this;
    }

    public RpcServerConfig bizQueueCapacity(int bizQueueCapacity) {
        this.bizQueueCapacity = bizQueueCapacity;
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
