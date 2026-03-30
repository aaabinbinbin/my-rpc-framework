package com.rpc.core.transport.netty.client;

import com.rpc.core.config.RpcClientConfig;
import com.rpc.core.discovery.ServiceDirectory;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.DefaultInvocationOptionsResolver;
import com.rpc.core.invoke.invocation.InvocationAttachmentKeys;
import com.rpc.core.invoke.invocation.InvocationOptions;
import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.RpcMessageType;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.protocol.codec.RpcProtocolDecoder;
import com.rpc.core.protocol.codec.RpcProtocolEncoder;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.ratelimit.RateLimiterManager;
import com.rpc.core.resilience.retry.DefaultRetryStrategy;
import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.netty.client.connection.RpcConnection;
import com.rpc.core.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.core.transport.netty.client.handler.RpcClientHandler;
import com.rpc.core.transport.netty.client.handler.heart.HeartbeatHandler;
import com.rpc.core.transport.netty.client.handler.heart.ReconnectHandler;
import com.rpc.core.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.core.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.core.transport.netty.client.manager.RequestManager;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class RpcNettyClient implements RpcTransport {
    // 用于区分“主动关闭客户端”和“链路异常断开”，避免正常退出时还继续重连。
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private EventLoopGroup eventLoopGroup;
    private ConnectionPool connectionPool;
    private RequestManager requestManager;
    private final ServiceDiscovery serviceDiscovery;
    private final ServiceDirectory serviceDirectory;
    private final int readTimeout;
    private final RpcClientInvocationExecutor invocationExecutor;
    private final byte serializerType;

    public RpcNettyClient(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
        // 服务目录负责订阅、缓存和回退，本类只通过它拿服务地址。
        this.serviceDirectory = new ServiceDirectory(
                serviceDiscovery,
                config.getDiscoveryCacheTtlMillis(),
                config.isDiscoveryAllowStaleOnFailure()
        );
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.readTimeout = config.getReadTimeout();
        // 默认序列化器类型在客户端初始化时缓存下来，避免每次请求都重复解析。
        this.serializerType = (byte) config.resolveSerializer().getSerializerType();

        LoadBalancer loadBalancer = config.getLoadBalancer();
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.configure(
                config.getCircuitBreakerFailureRateThreshold(),
                config.getCircuitBreakerMinNumberOfCalls(),
                config.getCircuitBreakerWaitDurationInOpenStateMillis(),
                config.getCircuitBreakerPermittedHalfOpenCalls()
        );

        RateLimiterManager rateLimiterManager = new RateLimiterManager();
        rateLimiterManager.configure(config.isRateLimitEnabled(), config.getRateLimitPermitsPerSecond());
        RetryExecutor retryExecutor = new RetryExecutor(new DefaultRetryStrategy(), config.getRetryTimes());

        if (config.isDiscoveryPreheatEnabled()) {
            serviceDirectory.preheat(config.getDiscoveryPreheatServices());
        }

        // 服务解析器负责选地址，调用执行器负责治理与调用编排。
        RpcServiceResolver serviceResolver = new RpcServiceResolver(serviceDirectory, loadBalancer, circuitBreakerManager);
        this.invocationExecutor = new RpcClientInvocationExecutor(
                serviceResolver,
                circuitBreakerManager,
                retryExecutor,
                new DefaultInvocationOptionsResolver(
                        InvocationOptions.builder()
                                .retryTimes(config.getRetryTimes())
                                .clusterStrategy(config.getClusterStrategy())
                                .rateLimitEnabled(config.isRateLimitEnabled())
                                .rateLimitPermitsPerSecond(config.getRateLimitPermitsPerSecond())
                                .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                                .build(),
                        config.getMethodConfigs()
                ),
                rateLimiterManager
        );

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new LoggingHandler(LogLevel.INFO))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 处理链顺序固定：空闲检测 -> 编解码 -> 心跳 -> 重连 -> 业务响应处理。
                        ch.pipeline()
                                .addLast("idleStateHandler",
                                        new IdleStateHandler(0, config.getHeartbeatInterval(), 0, TimeUnit.MILLISECONDS))
                                .addLast("decoder", new RpcProtocolDecoder())
                                .addLast("encoder", new RpcProtocolEncoder())
                                .addLast("heartbeatHandler", new HeartbeatHandler())
                                .addLast("reconnectHandler", new ReconnectHandler(connectionPool, closing, config))
                                .addLast("handler", new RpcClientHandler(requestManager));
                    }
                });

        this.connectionPool = new ConnectionPool(bootstrap);

        if (config.isEnableDegradation()) {
            log.info("Client degradation enabled, policy={}, threshold={}",
                    config.getDegradationPolicy() != null
                            ? config.getDegradationPolicy().getClass().getSimpleName()
                            : "null",
                    config.getDegradationFailureThreshold());
        }
    }

    @Override
    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        // 同步请求也先走统一的调用执行器，
        // 这样限流、熔断、方法级配置和集群策略都能复用一套入口。
        return invocationExecutor.execute(rpcRequest, this::sendRequestToAddress);
    }

    @Override
    public void sendRequestAsync(RpcRequest rpcRequest, long requestId) throws Exception {
        rpcRequest.setRequestId(String.valueOf(requestId));
        InetSocketAddress selectedAddress = invocationExecutor.resolveServiceAddress(rpcRequest.getServiceName());
        sendRequestAsyncToAddress(rpcRequest, requestId, selectedAddress);
    }

    private RpcResponse sendRequestToAddress(RpcRequest rpcRequest, InetSocketAddress address) throws Exception {
        long requestId = generateRequestId();
        rpcRequest.setRequestId(String.valueOf(requestId));
        // 请求管理器负责把 requestId 和 future 绑定起来，等响应回来后再完成 future。
        CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);

        RpcConnection connection = connectionPool.getConnection(address.getHostString(), address.getPort());
        RpcMessage message = buildRequestMessage(rpcRequest, requestId);

        connection.getChannel().writeAndFlush(message).sync();
        // 读取超时既可以走全局默认值，也可以被方法级配置覆盖。
        return future.get(resolveReadTimeout(rpcRequest), TimeUnit.MILLISECONDS);
    }

    private void sendRequestAsyncToAddress(RpcRequest rpcRequest, long requestId, InetSocketAddress address)
            throws Exception {
        RpcConnection connection = connectionPool.getConnection(address.getHostString(), address.getPort());
        RpcMessage message = buildRequestMessage(rpcRequest, requestId);

        connection.getChannel().writeAndFlush(message).addListener(future -> {
            if (!future.isSuccess()) {
                requestManager.failRequest(requestId, future.cause());
                log.error("Failed to send async request requestId={}", requestId, future.cause());
            }
        });
    }

    private RpcMessage buildRequestMessage(RpcRequest rpcRequest, long requestId) {
        byte requestSerializerType = resolveSerializerType(rpcRequest);
        // 序列化类型码要放进消息头里，服务端解码时才能知道该用哪种反序列化器。
        RpcHeader header = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(requestSerializerType)
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

    private int resolveReadTimeout(RpcRequest rpcRequest) {
        String override = rpcRequest.getAttachments().get(InvocationAttachmentKeys.READ_TIMEOUT);
        return override == null || override.isBlank() ? readTimeout : Integer.parseInt(override);
    }

    private byte resolveSerializerType(RpcRequest rpcRequest) {
        String serializerName = rpcRequest.getAttachments().get(InvocationAttachmentKeys.SERIALIZER);
        if (serializerName == null || serializerName.isBlank()) {
            return serializerType;
        }
        // 方法级序列化器覆盖最终也收敛成消息头中的类型码。
        return (byte) SerializerFactory.getSerializer(serializerName).getSerializerType();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        log.info("Closing Netty client...");

        if (connectionPool != null) {
            // 先关连接，让 channelInactive 感知到 closing=true 后不再调度重连。
            connectionPool.closeAll();
        }

        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }

        if (serviceDiscovery != null) {
            serviceDirectory.close();
            serviceDiscovery.close();
        }

        log.info("Netty client closed");
    }
}
