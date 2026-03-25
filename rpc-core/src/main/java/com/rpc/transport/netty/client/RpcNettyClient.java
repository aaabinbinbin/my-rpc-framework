package com.rpc.transport.netty.client;

import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;
import com.rpc.common.exception.dedicated.CircuitBreakerException;
import com.rpc.config.RpcClientConfig;
import com.rpc.faulttolerance.CircuitBreaker;
import com.rpc.faulttolerance.DegradationPolicy;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.faulttolerance.retry.DefaultRetryStrategy;
import com.rpc.faulttolerance.retry.RetryExecutor;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.protocol.*;
import com.rpc.registry.ServiceRegistry;
import com.rpc.serialize.factory.SerializerFactory;
import com.rpc.transport.netty.client.connection.RpcConnection;
import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.transport.netty.client.handler.RpcClientHandler;
import com.rpc.transport.netty.client.handler.heart.HeartbeatHandler;
import com.rpc.transport.netty.client.handler.heart.ReconnectHandler;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** 熔断器管理器 */
    private final CircuitBreakerManager circuitBreakerManager;

    /** 重试执行器 */
    private final RetryExecutor retryExecutor;
    
    // ========== 可选模块组件 ==========
    
    /** 降级策略（可选） */
    private final DegradationPolicy degradationPolicy;
    
    /** 是否启用降级 */
    private final boolean enableDegradation;
    
    /** 降级失败阈值 */
    private final int degradationFailureThreshold;
    
    /** 连续失败计数器（用于触发降级） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    // 配置
    private int readTimeout = 10000;     // 读取超时 10 秒

    /**
     * 带服务注册中心的构造方法
     * @param config 客户端配置（使用 Builder 模式简化创建）
     * @param serviceRegistry 服务注册中心
     */
    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.loadBalancer = config.getLoadBalancer();
        readTimeout = config.getReadTimeout();
        
        // 初始化容错组件
        this.circuitBreakerManager = CircuitBreakerManager.getInstance();
        this.retryExecutor = new RetryExecutor(
                new DefaultRetryStrategy(),
                config.getRetryTimes()
        );
        
        // 初始化可选模块组件
        this.degradationPolicy = config.getDegradationPolicy();
        this.enableDegradation = config.isEnableDegradation();
        this.degradationFailureThreshold = config.getDegradationFailureThreshold();
        
        if (enableDegradation) {
            log.info("已启用降级策略：policy={}, threshold={}",
                    degradationPolicy != null ? degradationPolicy.getClass().getSimpleName() : "null",
                    degradationFailureThreshold);
        }

        // 创建 Bootstrap
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

    /**
     * 发送 RPC 请求（集成重试、熔断、降级等功能）
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        String serviceName = rpcRequest.getServiceName();

        // 【可选模块】检查是否需要降级
        if (enableDegradation && shouldDegrade(serviceName)) {
            log.warn("触发降级策略：{}", serviceName);
            consecutiveFailures.incrementAndGet();
            
            if (degradationPolicy != null) {
                // 执行降级策略
                return degradationPolicy.degrade(rpcRequest, 
                        new RuntimeException("服务不可用，已触发降级"));
            } else {
                // 没有配置降级策略，快速失败
                throw new com.rpc.common.exception.RpcException(
                        com.rpc.common.constant.ErrorCode.SERVICE_DEGRADED,
                        "服务不可用，已触发降级但未配置降级策略");
            }
        }

        try {
            // 使用重试执行器，内部会调用 doSendRequestWithInstanceCircuitBreaker
            RpcResponse response = retryExecutor.executeWithRetry(rpcRequest,
                    () -> doSendRequestWithInstanceCircuitBreaker(rpcRequest));
            
            // 【可选模块】请求成功，重置连续失败计数
            resetFailureCount(serviceName);
            
            return response;
                
        } catch (com.rpc.common.exception.RpcException e) {
            // 重试失败，记录到服务级熔断器
            CircuitBreaker serviceCb = circuitBreakerManager.getServiceCircuitBreaker(serviceName);
            serviceCb.recordFailure();
            
            // 【可选模块】增加连续失败计数，可能触发降级
            int failures = consecutiveFailures.incrementAndGet();
            log.debug("连续失败次数：{}/{}", failures, degradationFailureThreshold);
            
            throw e;
        } catch (Exception e) {
            CircuitBreaker serviceCb = circuitBreakerManager.getServiceCircuitBreaker(serviceName);
            serviceCb.recordFailure();
            
            // 【可选模块】增加连续失败计数
            consecutiveFailures.incrementAndGet();
            
            throw new com.rpc.common.exception.RpcException(
                    com.rpc.common.constant.ErrorCode.SERVER_ERROR,
                    "RPC 调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 发送请求（带实例级熔断检查）
     */
    private RpcResponse doSendRequestWithInstanceCircuitBreaker(RpcRequest rpcRequest)
            throws Exception {
        String serviceName = rpcRequest.getServiceName();

        // 1. 生成请求 ID
        long requestId = generateRequestId();
        rpcRequest.setRequestId(String.valueOf(requestId));

        // 2. 创建 Future 用于接收响应
        CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);

        // 3. 从注册中心获取服务提供者列表
        List<InetSocketAddress> addresses = serviceRegistry.lookup(serviceName);

        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(ErrorCode.SERVICE_NOT_FOUND,
                    "服务未找到：" + serviceName);
        }

        // 4. 【关键】使用带熔断检查的负载均衡选择实例
        InetSocketAddress selectedAddress;
        try {
            selectedAddress = loadBalancer.selectWithCircuitBreaker(
                    serviceName, addresses, circuitBreakerManager);
        } catch (CircuitBreakerException e) {
            // 所有实例都熔断了
            log.error("所有服务实例都已熔断：{}", serviceName);
            throw e;
        }

        String host = selectedAddress.getAddress().getHostAddress();
        int port = selectedAddress.getPort();
        log.info("选择服务实例：{} -> {}", serviceName, selectedAddress);

        // 5. 获取连接
        RpcConnection connection = connectionPool.getConnection(host, port);

        // 6. 构建请求消息
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

        // 7. 发送消息
        connection.getChannel().writeAndFlush(message).sync();
        log.debug("请求已发送：{}.{}", rpcRequest.getServiceName(),
                rpcRequest.getMethodName());

        // 8. 同步等待响应（带超时）
        RpcResponse response = future.get(readTimeout, TimeUnit.MILLISECONDS);

        // 9. 检查响应状态
        if (response.getCode() != 200) {
            throw new RpcException(ErrorCode.SERVICE_EXCEPTION,
                    "RPC 调用失败：" + response.getMessage());
        }

        // 10. 【关键】记录成功到实例级熔断器
        CircuitBreaker instanceCb = circuitBreakerManager.getInstanceCircuitBreaker(
                serviceName, selectedAddress);
        instanceCb.recordSuccess();

        return response;
    }

    /**
     * 生成请求 ID
     */
    private long generateRequestId() {
        // 简单实现：使用时间戳
        return System.nanoTime();
    }

    // ========== 可选模块的辅助方法 ==========

    /**
     * 判断是否应该降级
     * @param serviceName 服务名称
     * @return true-降级，false-不降级
     */
    private boolean shouldDegrade(String serviceName) {
        // 检查熔断器状态（如果熔断器打开，也应该降级）
        CircuitBreaker circuitBreaker = circuitBreakerManager.getServiceCircuitBreaker(serviceName);
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            log.debug("熔断器已打开，触发降级：{}", serviceName);
            return true;
        }
        
        // 检查连续失败次数
        int failures = consecutiveFailures.get();
        if (failures >= degradationFailureThreshold) {
            log.warn("连续失败次数达到阈值，触发降级：{} (失败次数={}/{})",
                    serviceName, failures, degradationFailureThreshold);
            return true;
        }
        
        return false;
    }

    /**
     * 重置失败计数（调用成功后调用）
     * @param serviceName 服务名称
     */
    private void resetFailureCount(String serviceName) {
        int prevFailures = consecutiveFailures.getAndSet(0);
        if (prevFailures > 0) {
            log.info("服务恢复，重置失败计数：{} (之前失败次数={})", serviceName, prevFailures);
        }
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
