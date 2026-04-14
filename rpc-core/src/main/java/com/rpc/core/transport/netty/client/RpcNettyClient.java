package com.rpc.core.transport.netty.client;

import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.common.exception.RpcExceptionMapper;
import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.discovery.ServiceDirectory;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.DefaultInvocationOptionsResolver;
import com.rpc.core.invoke.invocation.InvocationAttachmentKeys;
import com.rpc.core.invoke.invocation.InvocationOptions;
import com.rpc.core.observability.metrics.ClientRuntimeMetricsManager;
import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
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
import com.rpc.core.transport.netty.client.handler.heartbeat.HeartbeatHandler;
import com.rpc.core.transport.netty.client.handler.heartbeat.ReconnectHandler;
import com.rpc.core.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.core.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.core.transport.netty.client.request.RequestManager;
import com.rpc.core.transport.netty.client.scheduler.ClientSharedScheduler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty 版 RPC 客户端。
 *
 * 所处阶段：consumer 侧“调用编排完成之后、真实网络发送之前”的 transport 层。
 * 主要职责：
 * 1. 初始化 Netty Bootstrap 和客户端 pipeline。
 * 2. 维护连接池、pending 请求表和超时扫描任务。
 * 3. 将 RpcRequest 编码成 RpcMessage 并写入目标 provider 连接。
 * 4. 在关闭时清理连接、pending 请求、服务发现订阅和 EventLoop。
 *
 * 注意事项：
 * - requestId 必须由调用编排层在真实 attempt 前生成，本类只校验和使用。
 * - 这里不做服务发现和负载均衡决策，只向已经选中的地址发送请求。
 * - 客户端高并发保护依赖 pending 上限、单连接 inflight 上限和连接池总量限制。
 */
@Slf4j
public class RpcNettyClient implements RpcTransport {
    private static final long MIN_TIMEOUT_SCAN_INTERVAL_MILLIS = 100L;
    private static final long MAX_TIMEOUT_SCAN_INTERVAL_MILLIS = 1_000L;

    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final ClientSharedScheduler timeoutScanner = ClientSharedScheduler.getInstance();
    private EventLoopGroup eventLoopGroup;
    private ConnectionPool connectionPool;
    private RequestManager requestManager;
    private final ServiceDiscovery serviceDiscovery;
    private final ServiceDirectory serviceDirectory;
    private final int readTimeout;
    private final RpcClientInvocationExecutor invocationExecutor;
    private final byte serializerType;
    private ScheduledFuture<?> timeoutScanTask;

    public RpcNettyClient(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
        this.serviceDirectory = new ServiceDirectory(
                serviceDiscovery,
                config.getDiscoveryCacheTtlMillis(),
                config.isDiscoveryAllowStaleOnFailure()
        );
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager(config.getMaxPendingRequests());
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
                .option(ChannelOption.TCP_NODELAY, true);
        if (log.isDebugEnabled()) {
            bootstrap.handler(new LoggingHandler(getClass(), io.netty.handler.logging.LogLevel.DEBUG));
        }
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("idleStateHandler",
                                        new IdleStateHandler(0, config.getHeartbeatInterval(), 0, TimeUnit.MILLISECONDS))
                                .addLast("decoder", new RpcProtocolDecoder())
                                .addLast("encoder", new RpcProtocolEncoder())
                                .addLast("heartbeatHandler", new HeartbeatHandler())
                                .addLast("reconnectHandler", new ReconnectHandler(
                                        () -> connectionPool,
                                        serviceDirectory::containsAddress,
                                        closing,
                                        config))
                                .addLast("handler", new RpcClientHandler(requestManager));
                    }
                });

        this.connectionPool = new ConnectionPool(
                bootstrap,
                config.getMaxInflightRequestsPerConnection(),
                config.getMaxConnectionsPerAddress(),
                config.getMaxTotalConnections(),
                config.getIdleConnectionTtlMillis(),
                config.getIdleConnectionEvictIntervalMillis()
        );
        scheduleTimeoutScanner();

        if (config.isEnableDegradation()) {
            log.info("Client degradation enabled, policy={}",
                    config.getDegradationPolicy() != null
                            ? config.getDegradationPolicy().getClass().getSimpleName()
                            : "null");
        }
    }

    /** consumer 侧统一发送入口，先进入调用编排链，再落到具体地址发送。 */
    @Override
    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        return invocationExecutor.execute(rpcRequest, this::sendRequestToAddress);
    }

    /**
     * 向已经选中的 provider 地址发送一次真实网络请求。
     *
     * 所处阶段：服务发现、负载均衡、熔断等逻辑已经完成；这里负责连接、写出、等待响应。
     * 边界处理：
     * - 单连接 inflight 已满时快速失败为 CLIENT_BUSY。
     * - 发送或等待异常统一映射为框架异常，并从 pending 表中清理。
     * - finally 中释放连接 inflight 名额，避免计数泄漏。
     */
    private RpcResponse sendRequestToAddress(RpcRequest rpcRequest, InetSocketAddress address) throws Exception {
        long requestId = parseRequestId(rpcRequest);
        int requestTimeout = resolveReadTimeout(rpcRequest);
        RpcConnection connection = null;
        boolean requestSlotAcquired = false;
        try {
            connection = connectionPool.getConnection(address.getHostString(), address.getPort());
            requestSlotAcquired = connection.tryAcquireRequestSlot();
            if (!requestSlotAcquired) {
                ClientRuntimeMetricsManager.getInstance().getMetrics().recordInflightLimitRejection();
                throw new RpcException(
                        ErrorCode.CLIENT_BUSY,
                        "RPC connection inflight limit exceeded for " + address
                );
            }
            CompletableFuture<RpcResponse> future =
                    requestManager.addRequest(requestId, connection.getChannel(), requestTimeout);
            RpcMessage message = buildRequestMessage(rpcRequest, requestId);

            connection.getChannel().writeAndFlush(message).sync();
            return future.get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Exception mapped = RpcExceptionMapper.fromTransport(e);
            requestManager.failRequest(requestId, mapped);
            throw mapped;
        } finally {
            if (connection != null && requestSlotAcquired) {
                connection.releaseRequestSlot();
            }
        }
    }

    /**
     * 启动 pending 请求超时扫描任务。
     *
     * 扫描周期按 readTimeout 做上下限裁剪，避免超时时间很小时扫描过于频繁，
     * 也避免超时时间很大时 pending 清理反应过慢。
     */
    private void scheduleTimeoutScanner() {
        long intervalMillis = Math.max(
                MIN_TIMEOUT_SCAN_INTERVAL_MILLIS,
                Math.min(readTimeout, MAX_TIMEOUT_SCAN_INTERVAL_MILLIS)
        );
        timeoutScanTask = timeoutScanner.scheduleAtFixedRate(
                () -> requestManager.clearTimeoutRequests(System.currentTimeMillis()),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    /** 构造协议消息头，serializerType 允许方法级配置覆盖客户端默认序列化器。 */
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

    /** transport 层只接受已经初始化好的 requestId；缺失说明上游调用编排顺序出错。 */
    private long parseRequestId(RpcRequest rpcRequest) {
        String requestId = rpcRequest.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalStateException("RPC requestId is not initialized before transport send");
        }
        return Long.parseLong(requestId);
    }

    /** 读取方法级超时覆盖值；未覆盖时使用客户端全局 readTimeout。 */
    private int resolveReadTimeout(RpcRequest rpcRequest) {
        String override = rpcRequest.getAttachments().get(InvocationAttachmentKeys.READ_TIMEOUT);
        return override == null || override.isBlank() ? readTimeout : Integer.parseInt(override);
    }

    /** 读取方法级序列化器覆盖值；未覆盖时使用客户端默认序列化器。 */
    private byte resolveSerializerType(RpcRequest rpcRequest) {
        String serializerName = rpcRequest.getAttachments().get(InvocationAttachmentKeys.SERIALIZER);
        if (serializerName == null || serializerName.isBlank()) {
            return serializerType;
        }
        return (byte) SerializerFactory.getSerializer(serializerName).getSerializerType();
    }

    /**
     * 关闭客户端运行时资源。
     *
     * 关闭顺序要先阻止新请求，再失败 pending 请求，最后释放连接池、EventLoop 和服务发现资源。
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        log.info("Closing Netty client...");

        if (timeoutScanTask != null) {
            timeoutScanTask.cancel(false);
            timeoutScanTask = null;
        }
        timeoutScanner.release();

        if (requestManager != null) {
            requestManager.failAll(new IllegalStateException("Netty client is closing"));
        }

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
