package com.rpc.core.transport.netty.server;

import com.rpc.core.protocol.codec.RpcProtocolDecoder;
import com.rpc.core.protocol.codec.RpcProtocolEncoder;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.local.LocalRegistryImpl;
import com.rpc.core.runtime.server.BizThreadPool;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.config.server.RpcServerConfig;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestDispatcher;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestExecutor;
import com.rpc.core.transport.netty.server.handler.RpcRequestHandler;
import com.rpc.core.transport.netty.server.handler.heartbeat.ServerHeartbeatHandler;
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
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Netty 的服务端实现。
 * 这个类是 provider 真正监听端口、接收请求、组装处理链的核心入口。
 *
 * 它主要负责三件事：
 * 1. 启动 Netty Server，把端口监听起来。
 * 2. 组装服务端 pipeline，把心跳、编解码、请求处理这些环节串起来。
 * 3. 在停机时按顺序摘服务、等待请求执行完、再关闭线程池和网络资源。
 */
@Slf4j
public class RpcNettyServer implements RpcServer {
    /** 服务端运行时配置，例如端口、线程数、空闲检测时间等。 */
    private final RpcServerConfig config;
    /** provider 本地注册表，用来保存“接口名 -> 实现对象”的映射。 */
    private final LocalRegistry localRegistry;
    /** 请求处理入口，对 Netty handler 层屏蔽具体业务分发细节。 */
    private final RpcRequestProcessor requestProcessor;
    /** 业务线程池，专门执行真正的服务方法，避免阻塞 Netty 的 IO 线程。 */
    private final ExecutorService bizExecutor;
    /** 服务端生命周期控制器，用来协调优雅停机和请求执行中的状态。 */
    private final ServerLifecycle serverLifecycle;
    /** Netty boss 线程组，负责接收新连接。 */
    private EventLoopGroup bossGroup;
    /** Netty worker 线程组，负责处理已建立连接上的 IO 事件。 */
    private EventLoopGroup workerGroup;
    /** 服务端监听 Channel，用于 shutdown 时主动关闭端口监听。 */
    private Channel serverChannel;
    /** 防止 shutdown 流程重复执行。 */
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    /**
     * 创建 Netty 服务端。
     *
     * 构造阶段就会把本地注册表、业务线程池、请求分发器这些基础设施准备好，
     * 这样 start() 只需要专注于网络层启动。
     */
    public RpcNettyServer(RpcServerConfig config, ServiceRegistry registry) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl(registry, config.getHost(), config.getPort());
        this.serverLifecycle = new ServerLifecycle();
        // 真正的业务执行都下沉到独立线程池，避免拖慢 Netty 的网络读写线程。
        this.bizExecutor = BizThreadPool.create(
                config.getBizCoreThreads(),
                config.getBizMaxThreads(),
                config.getBizQueueCapacity()
        );
        this.requestProcessor = new RpcRequestDispatcher(
                new RpcRequestExecutor(localRegistry, serverLifecycle),
                serverLifecycle
        );
    }

    @Override
    /**
     * 启动服务端并阻塞等待关闭。
     *
     * pipeline 顺序大致是：
     * 空闲检测 -> 心跳处理 -> 协议解码 -> 协议编码 -> 请求处理。
     */
    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(config.getBossThreads());
        workerGroup = new NioEventLoopGroup(config.getWorkerThreads());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            if (log.isDebugEnabled()) {
                bootstrap.handler(new LoggingHandler(getClass(), io.netty.handler.logging.LogLevel.DEBUG));
            }
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 服务端 pipeline 的顺序很关键：
                            // 1. 空闲检测识别长时间无读写的连接；
                            // 2. 心跳处理负责保活；
                            // 3. 解码把字节流还原成 RPC 消息；
                            // 4. 编码负责回包；
                            // 5. 最后才进入业务请求处理。
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
                                    .addLast("handler", new RpcRequestHandler(requestProcessor, bizExecutor));
                        }
                    });

            ChannelFuture future = bootstrap.bind(new InetSocketAddress(config.getHost(), config.getPort())).sync();
            serverChannel = future.channel();
            // 阻塞等待 closeFuture，表示当前线程托管整个 server 生命周期。
            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    @Override
    /**
     * 优雅关闭服务端。
     *
     * 关闭顺序不能乱：
     * 1. 先拒绝新请求；
     * 2. 再从注册中心摘服务；
     * 3. 等待正在执行的请求完成；
     * 4. 最后关闭网络和线程池。
     */
    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        serverLifecycle.stopAcceptingRequests();

        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }
        serverLifecycle.awaitDrained(config.getShutdownTimeout(), TimeUnit.SECONDS);
        unregisterAllServices();
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
    /** 暴露本地注册表给上层，用于注册或查询当前 provider 持有的服务对象。 */
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }

    /**
     * 停机时逐个从注册中心摘除已经注册的服务。
     *
     * 这里选择“尽量继续往下执行”，因为停机阶段的目标是完成最大程度清理，
     * 不能因为某一个服务摘除失败就卡住整个 shutdown 流程。
     */
    private void unregisterAllServices() {
        for (String serviceName : localRegistry.serviceNames()) {
            try {
                localRegistry.unregister(serviceName);
            } catch (Exception e) {
                log.warn("Failed to unregister service during shutdown: {}", serviceName, e);
            }
        }
    }
}
