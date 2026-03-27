package com.rpc.transport.netty.server;

import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.registry.LocalRegistry;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.LocalRegistryImpl;
import com.rpc.transport.RpcServer;
import com.rpc.transport.netty.server.config.RpcServerConfig;
import com.rpc.transport.netty.server.dispatch.RpcRequestDispatcher;
import com.rpc.transport.netty.server.dispatch.RpcRequestExecutor;
import com.rpc.transport.netty.server.handler.RpcRequestHandler;
import com.rpc.transport.netty.server.handler.heart.ServerHeartbeatHandler;
import com.rpc.transport.netty.server.statistics.StatisticsManager;
import com.rpc.transport.server.RpcRequestProcessor;
import io.netty.bootstrap.ServerBootstrap;
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
import java.util.concurrent.TimeUnit;

@Slf4j
public class RpcNettyServer implements RpcServer {
    private final RpcServerConfig config;
    private final LocalRegistry localRegistry;
    private final RpcRequestProcessor requestProcessor;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public RpcNettyServer(RpcServerConfig config, ServiceRegistry registry) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl(registry, config.getHost(), config.getPort());
        this.requestProcessor = new RpcRequestDispatcher(new RpcRequestExecutor(localRegistry));
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

            InetSocketAddress address = new InetSocketAddress(config.getPort());
            ChannelFuture future = bootstrap.bind(address).sync();

            log.info("========================================");
            log.info("RPC 服务端启动成功");
            log.info("监听端口: {}", config.getPort());
            log.info("Boss 线程数: {}", config.getBossThreads());
            log.info("Worker 线程数: {}", config.getWorkerThreads());
            log.info("========================================");

            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    @Override
    public void shutdown() {
        log.info("正在关闭 RPC 服务端...");

        if (bossGroup != null) {
            bossGroup.shutdownGracefully()
                    .awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully()
                    .awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }

        StatisticsManager.getInstance().shutdown();
        log.info("RPC 服务端已关闭");
    }

    @SuppressWarnings("unchecked")
    @Override
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }
}
