package com.rpc.core.transport.netty.server;

import com.rpc.core.protocol.codec.RpcProtocolDecoder;
import com.rpc.core.protocol.codec.RpcProtocolEncoder;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.impl.LocalRegistryImpl;
import com.rpc.core.runtime.server.BizThreadPool;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.netty.server.config.RpcServerConfig;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestDispatcher;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestExecutor;
import com.rpc.core.transport.netty.server.handler.RpcRequestHandler;
import com.rpc.core.transport.netty.server.handler.heart.ServerHeartbeatHandler;
import com.rpc.core.transport.netty.server.statistics.StatisticsManager;
import com.rpc.core.transport.server.RpcRequestProcessor;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RpcNettyServer implements RpcServer {
    private final RpcServerConfig config;
    private final LocalRegistry localRegistry;
    private final RpcRequestProcessor requestProcessor;
    // 业务执行单独下沉到线程池，避免阻塞 Netty 的 IO 线程。
    private final ExecutorService bizExecutor;
    private final ServerLifecycle serverLifecycle;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public RpcNettyServer(RpcServerConfig config, ServiceRegistry registry) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl(registry, config.getHost(), config.getPort());
        this.serverLifecycle = new ServerLifecycle();
        // 服务提供端线程池隔离的入口就在这里，后续所有业务方法执行都经由 bizExecutor。
        this.bizExecutor = BizThreadPool.create(
                config.getBizCoreThreads(),
                config.getBizMaxThreads(),
                config.getBizQueueCapacity()
        );
        this.requestProcessor = new RpcRequestDispatcher(
                new RpcRequestExecutor(localRegistry, bizExecutor, serverLifecycle),
                serverLifecycle
        );
    }

    @Override
    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(config.getBossThreads());
        workerGroup = new NioEventLoopGroup(config.getWorkerThreads());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 服务端 pipeline 顺序：空闲检测 -> 心跳监控 -> 编解码 -> 请求处理。
                            ch.pipeline()
                                    .addLast("idleStateHandler",
                                            new IdleStateHandler(
                                                    config.getReaderIdleTime(),
                                                    config.getWriterIdleTime(),
                                                    config.getAllIdleTime(),
                                                    TimeUnit.MILLISECONDS))
                                    .addLast("serverHeartbeatHandler", new ServerHeartbeatHandler())
                                    .addLast("decoder", new RpcProtocolDecoder())
                                    .addLast("encoder", new RpcProtocolEncoder())
                                    .addLast("handler", new RpcRequestHandler(requestProcessor));
                        }
                    });

            ChannelFuture future = bootstrap.bind(new InetSocketAddress(config.getPort())).sync();
            serverChannel = future.channel();
            // start() 在这里阻塞等待，是为了让上层能把 server 生命周期完整托管给当前线程。
            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    @Override
    public void shutdown() {
        // 优雅停机顺序：
        // 1. 拒绝新请求
        // 2. 从注册中心摘服务
        // 3. 等待 in-flight 请求完成
        // 4. 再关网络线程和业务线程池
        serverLifecycle.stopAcceptingRequests();
        unregisterAllServices();
        serverLifecycle.awaitDrained(config.getShutdownTimeout(), TimeUnit.SECONDS);

        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }
        if (bizExecutor != null) {
            bizExecutor.shutdown();
            try {
                if (!bizExecutor.awaitTermination(config.getShutdownTimeout(), TimeUnit.SECONDS)) {
                    bizExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                bizExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        StatisticsManager.getInstance().shutdown();
    }

    @Override
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }

    private void unregisterAllServices() {
        for (String serviceName : localRegistry.serviceNames()) {
            try {
                localRegistry.unregister(serviceName);
            } catch (Exception e) {
                // 停机阶段尽量继续往下走，单个服务摘除失败不应该阻塞整个 shutdown。
                log.warn("Failed to unregister service during shutdown: {}", serviceName, e);
            }
        }
    }
}
