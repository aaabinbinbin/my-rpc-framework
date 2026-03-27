package com.rpc.transport.netty.client;

import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.config.RpcClientConfig;
import com.rpc.faulttolerance.DegradationPolicy;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.faulttolerance.retry.DefaultRetryStrategy;
import com.rpc.faulttolerance.retry.RetryExecutor;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.registry.ServiceRegistry;
import com.rpc.serialize.factory.SerializerFactory;
import com.rpc.transport.RpcTransport;
import com.rpc.transport.netty.client.connection.RpcConnection;
import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.transport.netty.client.handler.RpcClientHandler;
import com.rpc.transport.netty.client.handler.heart.HeartbeatHandler;
import com.rpc.transport.netty.client.handler.heart.ReconnectHandler;
import com.rpc.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.transport.netty.client.manager.RequestManager;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty client.
 */
@Slf4j
public class RpcNettyClient implements RpcTransport {
    private EventLoopGroup eventLoopGroup;
    private ConnectionPool connectionPool;
    private RequestManager requestManager;
    private final ServiceRegistry serviceRegistry;
    private int readTimeout = 10000;
    private final RpcClientInvocationExecutor invocationExecutor;

    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.readTimeout = config.getReadTimeout();

        LoadBalancer loadBalancer = config.getLoadBalancer();
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        RetryExecutor retryExecutor = new RetryExecutor(new DefaultRetryStrategy(), config.getRetryTimes());
        DegradationPolicy degradationPolicy = config.getDegradationPolicy();
        boolean enableDegradation = config.isEnableDegradation();
        int degradationFailureThreshold = config.getDegradationFailureThreshold();

        RpcServiceResolver serviceResolver = new RpcServiceResolver(serviceRegistry, loadBalancer, circuitBreakerManager);
        this.invocationExecutor = new RpcClientInvocationExecutor(
                serviceResolver,
                circuitBreakerManager,
                retryExecutor,
                degradationPolicy,
                enableDegradation,
                degradationFailureThreshold
        );

        if (enableDegradation) {
            log.info("已启用降级策略: policy={}, threshold={}",
                    degradationPolicy != null ? degradationPolicy.getClass().getSimpleName() : "null",
                    degradationFailureThreshold);
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new LoggingHandler(LogLevel.INFO))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("idleStateHandler",
                                        new IdleStateHandler(0, config.getHeartbeatInterval(), 0, TimeUnit.SECONDS))
                                .addLast("decoder", new RpcProtocolDecoder())
                                .addLast("encoder", new RpcProtocolEncoder())
                                .addLast("heartbeatHandler", new HeartbeatHandler())
                                .addLast("reconnectHandler", new ReconnectHandler(connectionPool))
                                .addLast("handler", new RpcClientHandler(requestManager));
                    }
                });

        this.connectionPool = new ConnectionPool(bootstrap);
    }

    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        return invocationExecutor.execute(rpcRequest, this::sendRequestToAddress);
    }

    public void sendRequestAsync(RpcRequest rpcRequest, long requestId) throws Exception {
        rpcRequest.setRequestId(String.valueOf(requestId));
        InetSocketAddress selectedAddress = invocationExecutor.resolveServiceAddress(rpcRequest.getServiceName());
        sendRequestAsyncToAddress(rpcRequest, requestId, selectedAddress);
    }

    private RpcResponse sendRequestToAddress(RpcRequest rpcRequest, InetSocketAddress address) throws Exception {
        long requestId = generateRequestId();
        rpcRequest.setRequestId(String.valueOf(requestId));
        CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);

        RpcConnection connection = connectionPool.getConnection(address.getHostString(), address.getPort());
        RpcMessage message = buildRequestMessage(rpcRequest, requestId);

        connection.getChannel().writeAndFlush(message).sync();
        log.debug("请求已发送: {}.{}", rpcRequest.getServiceName(), rpcRequest.getMethodName());
        return future.get(readTimeout, TimeUnit.MILLISECONDS);
    }

    private void sendRequestAsyncToAddress(RpcRequest rpcRequest, long requestId, InetSocketAddress address)
            throws Exception {
        RpcConnection connection = connectionPool.getConnection(address.getHostString(), address.getPort());
        RpcMessage message = buildRequestMessage(rpcRequest, requestId);

        connection.getChannel().writeAndFlush(message).addListener(future -> {
            if (!future.isSuccess()) {
                requestManager.failRequest(requestId, future.cause());
                log.error("发送请求失败: requestId={}", requestId, future.cause());
            } else {
                log.debug("请求发送成功: requestId={}", requestId);
            }
        });
    }

    private RpcMessage buildRequestMessage(RpcRequest rpcRequest, long requestId) {
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
        return message;
    }

    private long generateRequestId() {
        return System.nanoTime();
    }

    public void close() {
        log.info("正在关闭客户端...");

        if (connectionPool != null) {
            connectionPool.closeAll();
        }

        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully()
                    .awaitUninterruptibly(5, TimeUnit.SECONDS);
        }

        if (serviceRegistry != null) {
            serviceRegistry.close();
        }

        log.info("客户端已关闭");
    }
}
