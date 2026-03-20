package com.rpc.transport.netty.client;

import com.rpc.transport.netty.client.connection.RpcConnection;
import com.rpc.transport.netty.client.manager.RequestManager;
import com.rpc.transport.netty.client.handler.RpcClientHandler;
import com.rpc.transport.netty.client.pool.ConnectionPool;
import com.rpc.protocol.*;
import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.serialize.factory.SerializerFactory;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 客户端
 */
@Slf4j
public class RpcNettyClient {
    private EventLoopGroup eventLoopGroup;
    private ConnectionPool connectionPool;
    private RequestManager requestManager;

    private int connectTimeout = 5000;  // 连接超时 5 秒
    private int readTimeout = 10000;     // 读取超时 10 秒

    public RpcNettyClient() {
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();

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
                                        new IdleStateHandler(0, 30, 0, TimeUnit.SECONDS))
                                .addLast("decoder", new RpcProtocolDecoder())
                                .addLast("encoder", new RpcProtocolEncoder())
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
        return sendRequest(rpcRequest, "127.0.0.1", 8080);
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
                    .messageType(RpcMessageType.REQUEST)
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
     * 异步发送请求
     */
    public CompletableFuture<RpcResponse> sendRequestAsync(
            RpcRequest rpcRequest, String host, int port) {

        try {
            // 1. 生成请求 ID
            long requestId = generateRequestId();
            rpcRequest.setRequestId(String.valueOf(requestId));

            // 2. 创建 Future
            CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);

            // 3. 获取连接
            RpcConnection connection = connectionPool.getConnection(host, port);

            // 4. 构建消息
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                    .messageType(RpcMessageType.REQUEST)
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();

            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(rpcRequest);

            // 5. 异步发送
            connection.getChannel().writeAndFlush(message)
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            requestManager.failRequest(requestId, f.cause());
                        }
                    });

            return future;

        } catch (Exception e) {
            log.error("异步发送请求失败", e);
            CompletableFuture<RpcResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
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

        log.info("客户端已关闭");
    }
}
