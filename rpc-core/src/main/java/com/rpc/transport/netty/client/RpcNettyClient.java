package com.rpc.transport.netty.client;

import com.rpc.config.RpcClientConfig;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.transport.netty.client.connection.RpcConnection;
import com.rpc.transport.netty.client.handler.heart.HeartbeatHandler;
import com.rpc.transport.netty.client.handler.heart.ReconnectHandler;
import com.rpc.transport.netty.client.manager.RequestManager;
import com.rpc.transport.netty.client.handler.RpcClientHandler;
import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.protocol.*;
import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.serialize.factory.SerializerFactory;
import com.rpc.registry.ServiceRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 客户端
 */
@Slf4j
public class RpcNettyClient {
    // Netty 事件循环组
    private EventLoopGroup eventLoopGroup;
    // 连接池
    private ConnectionPool connectionPool;
    // 请求管理器
    private RequestManager requestManager;
    // 服务注册中心
    private final ServiceRegistry serviceRegistry;
    // 负载均衡器
    private final LoadBalancer loadBalancer;
    // 配置
    private int connectTimeout = 5000;  // 连接超时 5 秒
    private int readTimeout = 10000;     // 读取超时 10 秒
    private static final int HEARTBEAT_INTERVAL = 30;  // 心跳间隔（秒）

    /**
     * 带服务注册中心的构造方法
     * @param serviceRegistry 服务注册中心
     */
    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.loadBalancer = config.getLoadBalancer();

        // 创建 Bootstrap
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new LoggingHandler(LogLevel.INFO))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("idleStateHandler",
                                        new IdleStateHandler(0, HEARTBEAT_INTERVAL, 0, TimeUnit.SECONDS))
                                .addLast("decoder", new RpcProtocolDecoder())
                                .addLast("encoder", new RpcProtocolEncoder())
                                .addLast("heartbeatHandler", new HeartbeatHandler())
                                .addLast("reconnectHandler", new ReconnectHandler(connectionPool))
                                .addLast("handler", new RpcClientHandler(requestManager));
                    }
                });

        this.connectionPool = new ConnectionPool(bootstrap);
    }

    /**
     * 发送 RPC 请求（同步方式）
     * @param rpcRequest RPC 请求对象
     * @return RPC 响应
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest) {
        // 如果配置了服务注册中心，则从注册中心获取地址
        if (serviceRegistry != null) {
            return sendRequestWithServiceDiscovery(rpcRequest);
        } else {
            // 否则使用默认地址
            return sendRequest(rpcRequest, "127.0.0.1", 8080);
        }
    }

    /**
     * 基于服务发现发送请求
     */
    private RpcResponse sendRequestWithServiceDiscovery(RpcRequest rpcRequest) {
        try {
            // 1. 从注册中心获取服务提供者列表
            List<InetSocketAddress> addresses = serviceRegistry.lookup(rpcRequest.getServiceName());

            if (addresses == null || addresses.isEmpty()) {
                throw new RuntimeException("未找到服务：" + rpcRequest.getServiceName());
            }

            // 2. 简单选择第一个地址（后续可扩展负载均衡）
            InetSocketAddress address = loadBalancer.select(rpcRequest.getServiceName(), addresses);
            log.info("服务发现选择地址：{}", address);

            // 3. 发送到选中的地址
            return sendRequest(rpcRequest,
                    address.getAddress().getHostAddress(),
                    address.getPort());

        } catch (Exception e) {
            log.error("服务发现调用失败", e);
            throw new RuntimeException("服务发现调用失败", e);
        }
    }

    /**
     * 发送 RPC 请求到指定服务器
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest, String host, int port) {
        try {
            // 1. 生成请求 ID
            long requestId = generateRequestId();
            rpcRequest.setRequestId(String.valueOf(requestId));

            // 2. 创建 Future 用于接收响应
            CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);

            // 3. 获取连接
            RpcConnection connection = connectionPool.getConnection(host, port);

            // 4. 构建请求消息
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                    .messageType(RpcMessageType.REQUEST.getCode())
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();

            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(rpcRequest);

            // 5. 发送消息
            connection.getChannel().writeAndFlush(message).sync();
            log.debug("请求已发送：{}.{}", rpcRequest.getServiceName(),
                    rpcRequest.getMethodName());

            // 6. 同步等待响应（带超时）
            RpcResponse response = future.get(readTimeout, TimeUnit.MILLISECONDS);

            // 7. 检查响应状态
            if (response.getCode() != 200) {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }

            return response;

        } catch (Exception e) {
            log.error("发送请求失败", e);
            requestManager.failRequest(Long.parseLong(rpcRequest.getRequestId()), e);
            throw new RuntimeException("发送请求失败", e);
        }
    }

    /**
     * 生成请求 ID
     */
    private long generateRequestId() {
        // 简单实现：使用时间戳
        return System.nanoTime();
    }

    /**
     * 关闭客户端
     */
    public void close() {
        log.info("正在关闭客户端...");

        if (connectionPool != null) {
            connectionPool.closeAll();
        }

        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully()
                    .awaitUninterruptibly(5, TimeUnit.SECONDS);
        }

        // 关闭服务注册中心
        if (serviceRegistry != null) {
            serviceRegistry.close();
        }

        log.info("客户端已关闭");
    }
}
