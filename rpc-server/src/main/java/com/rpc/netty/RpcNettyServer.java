package com.rpc.netty;

import com.rpc.config.RpcServerConfig;
import com.rpc.netty.handler.RpcRequestHandler;
import com.rpc.protocol.codec.RpcProtocolDecoder;
import com.rpc.protocol.codec.RpcProtocolEncoder;
import com.rpc.netty.registry.LocalRegistry;
import com.rpc.netty.registry.impl.LocalRegistryImpl;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 服务端
 */
@Slf4j
public class RpcNettyServer {
    private final RpcServerConfig config;
    private final LocalRegistry localRegistry;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private final ScheduledExecutorService statsExecutor = Executors.newSingleThreadScheduledExecutor();


    public RpcNettyServer(RpcServerConfig config) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl();
    }

    /**
     * 启动服务器
     */
    public void start() throws Exception {
        // 启动统计定时任务（每分钟打印一次）
        statsExecutor.scheduleAtFixedRate(() -> {
            localRegistry.printAllStatistics();
        }, 1, 10, TimeUnit.SECONDS);
        // 1. 创建事件循环组
        bossGroup = new NioEventLoopGroup(config.getBossThreads());
        workerGroup = new NioEventLoopGroup(config.getWorkerThreads());

        try {
            // 2. 创建服务器启动引导
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))  // 日志处理器
                    .childOption(ChannelOption.TCP_NODELAY, true)  // 禁用 Nagle 算法
                    .childOption(ChannelOption.SO_KEEPALIVE, true)  // 保持长连接
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 3. 配置处理器链
                            ch.pipeline()
                                    // 入站处理器（按顺序执行）
                                    .addLast("idleStateHandler",
                                            new IdleStateHandler(30, 0,
                                                    0, TimeUnit.SECONDS)) // 空闲检测处理器 0 - 不检测
                                    .addLast("decoder", new RpcProtocolDecoder()) // 解码
                                    .addLast("encoder", new RpcProtocolEncoder()) // 编码  出站处理器，出站时才会使用
                                    // 编码器必须在处理器前面
                                    .addLast("handler", new RpcRequestHandler(localRegistry)); // 处理请求
                        }
                    });

            // 4. 绑定端口并启动
            InetSocketAddress address = new InetSocketAddress(config.getPort());
            ChannelFuture future = bootstrap.bind(address).sync();

            log.info("========================================");
            log.info("RPC 服务器启动成功");
            log.info("监听端口：{}", config.getPort());
            log.info("Boss 线程数：{}", config.getBossThreads());
            log.info("Worker 线程数：{}", config.getWorkerThreads());
            log.info("========================================");

            // 5. 等待服务关闭
            future.channel().closeFuture().sync();

        } finally {
            shutdown();
        }
    }

    /**
     * 优雅关闭
     */
    public void shutdown() {
        log.info("正在关闭 RPC 服务器...");

        if (bossGroup != null) {
            bossGroup.shutdownGracefully()
                    .awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully()
                    .awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }

        if (!statsExecutor.isShutdown()) {
            statsExecutor.shutdown();
        }

        log.info("RPC 服务器已关闭");
    }

    /**
     * 获取本地服务注册表
     */
    @SuppressWarnings("unchecked")
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }
}
