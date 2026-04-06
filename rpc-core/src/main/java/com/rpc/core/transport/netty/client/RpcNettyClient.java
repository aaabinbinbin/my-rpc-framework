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

/**
 * 基于 Netty 的 RPC 客户端实现。
 *
 * 这个类位于 consumer 侧调用链的 transport 层，
 * 负责把已经编排完成的 RpcRequest 真正发到远端，并异步接收响应。
 *
 * 可以把它理解成“网络发送与连接管理总入口”，
 * 但调用策略、限流、重试等高层逻辑并不直接写在这里，
 * 而是委托给 RpcClientInvocationExecutor 先完成编排。
 */
@Slf4j
public class RpcNettyClient implements RpcTransport {
    /**
     * 标记当前关闭动作是否是主动关闭。
     *
     * 这个标记主要给重连逻辑使用，
     * 防止应用正常关闭时还把断链误判为网络异常并继续触发重连。
     */
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /** Netty 事件循环组。 */
    private EventLoopGroup eventLoopGroup;

    /** 长连接池，负责按地址复用现有连接。 */
    private ConnectionPool connectionPool;

    /** 请求管理器，负责把 requestId 和等待中的 future 关联起来。 */
    private RequestManager requestManager;

    /** 原始服务发现组件。 */
    private final ServiceDiscovery serviceDiscovery;

    /** 服务目录，对服务发现结果做缓存、预热和失败回退。 */
    private final ServiceDirectory serviceDirectory;

    /** 默认读取超时。 */
    private final int readTimeout;

    /** 调用编排器，负责在真正发请求前完成限流、配置解析、容错等逻辑。 */
    private final RpcClientInvocationExecutor invocationExecutor;

    /** 默认序列化类型码，构造阶段缓存下来以减少重复解析。 */
    private final byte serializerType;

    public RpcNettyClient(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
        this.serviceDirectory = new ServiceDirectory(
                serviceDiscovery,
                config.getDiscoveryCacheTtlMillis(),
                config.isDiscoveryAllowStaleOnFailure()
        );
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.readTimeout = config.getReadTimeout();
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
                        // pipeline 顺序体现了 transport 层对一条连接的完整处理流程：
                        // 空闲检测 -> 协议解码/编码 -> 心跳保活 -> 断线重连 -> 业务响应处理。
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

    /**
     * 同步发送请求。
     *
     * 虽然对上层暴露的是同步接口，
     * 但底层仍然是“发送后注册 future，再等待异步响应回填”的模型。
     */
    @Override
    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        return invocationExecutor.execute(rpcRequest, this::sendRequestToAddress);
    }

    /**
     * 异步发送请求。
     *
     * 这里不阻塞等待响应，而是只负责把请求送到已解析出的目标地址。
     */
    @Override
    public void sendRequestAsync(RpcRequest rpcRequest, long requestId) throws Exception {
        rpcRequest.setRequestId(String.valueOf(requestId));
        InetSocketAddress selectedAddress = invocationExecutor.resolveServiceAddress(rpcRequest.getServiceName());
        sendRequestAsyncToAddress(rpcRequest, requestId, selectedAddress);
    }

    /**
     * 把请求发送到某个具体地址，并同步等待响应。
     *
     * 关键动作：
     * 1. 生成 requestId。
     * 2. 在 RequestManager 中登记 future。
     * 3. 从连接池拿连接。
     * 4. 把 RpcRequest 包装成 RpcMessage。
     * 5. 发送后等待 future 完成。
     */
    private RpcResponse sendRequestToAddress(RpcRequest rpcRequest, InetSocketAddress address) throws Exception {
        long requestId = generateRequestId();
        rpcRequest.setRequestId(String.valueOf(requestId));
        CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);

        RpcConnection connection = connectionPool.getConnection(address.getHostString(), address.getPort());
        RpcMessage message = buildRequestMessage(rpcRequest, requestId);

        connection.getChannel().writeAndFlush(message).sync();
        return future.get(resolveReadTimeout(rpcRequest), TimeUnit.MILLISECONDS);
    }

    /**
     * 异步发送到指定地址。
     *
     * 如果发送失败，需要主动通知 RequestManager 完成异常回填，
     * 否则等待中的请求方可能一直拿不到结果。
     */
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

    /**
     * 构造真正在线路上传输的 RpcMessage。
     *
     * 这里会根据当前请求的附件决定使用哪种序列化器，
     * 并把序列化类型码写入协议头，供服务端解码时使用。
     */
    private RpcMessage buildRequestMessage(RpcRequest rpcRequest, long requestId) {
        byte requestSerializerType = resolveSerializerType(rpcRequest);
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

    /** 生成请求 ID。当前实现直接使用纳秒时间，重点是保证短时间内足够区分请求。 */
    private long generateRequestId() {
        return System.nanoTime();
    }

    /**
     * 解析本次调用的读取超时。
     *
     * 优先使用方法级覆盖值；
     * 如果没有覆盖，则回退到客户端全局默认超时。
     */
    private int resolveReadTimeout(RpcRequest rpcRequest) {
        String override = rpcRequest.getAttachments().get(InvocationAttachmentKeys.READ_TIMEOUT);
        return override == null || override.isBlank() ? readTimeout : Integer.parseInt(override);
    }

    /**
     * 解析本次请求最终应使用的序列化类型码。
     *
     * 如果方法级配置覆盖了序列化器，则使用覆盖值；
     * 否则使用客户端默认序列化器。
     */
    private byte resolveSerializerType(RpcRequest rpcRequest) {
        String serializerName = rpcRequest.getAttachments().get(InvocationAttachmentKeys.SERIALIZER);
        if (serializerName == null || serializerName.isBlank()) {
            return serializerType;
        }
        return (byte) SerializerFactory.getSerializer(serializerName).getSerializerType();
    }

    /**
     * 关闭客户端。
     *
     * 关闭顺序也很重要：
     * 1. 先标记主动关闭，避免重连逻辑误触发。
     * 2. 再关闭连接池。
     * 3. 再关闭事件循环组。
     * 4. 最后关闭服务发现和目录资源。
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        log.info("Closing Netty client...");

        if (connectionPool != null) {
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
