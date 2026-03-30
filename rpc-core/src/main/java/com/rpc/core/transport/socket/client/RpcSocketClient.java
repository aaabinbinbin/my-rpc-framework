package com.rpc.core.transport.socket.client;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
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
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.ratelimit.RateLimiterManager;
import com.rpc.core.resilience.retry.DefaultRetryStrategy;
import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.core.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.core.transport.socket.SocketMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class RpcSocketClient implements RpcTransport {
    /**
     * Socket（套接字）版不维护长连接池和请求复用，但仍然复用与
     * Netty（网络通信框架）客户端相同的服务发现、方法级配置解析、
     * 负载均衡、重试、熔断和限流编排逻辑。
     * 这样传输层差异只留在“如何把请求发到某个地址”这一层。
     */
    private final ServiceDiscovery serviceDiscovery;
    private final ServiceDirectory serviceDirectory;
    private final int connectTimeout;
    private final int readTimeout;
    private final RpcClientInvocationExecutor invocationExecutor;
    private final byte serializerType;

    public RpcSocketClient(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
        this.serviceDirectory = new ServiceDirectory(
                serviceDiscovery,
                config.getDiscoveryCacheTtlMillis(),
                config.isDiscoveryAllowStaleOnFailure()
        );
        this.connectTimeout = config.getConnectTimeout();
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
    }

    @Override
    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        return invocationExecutor.execute(rpcRequest, this::sendRequestToAddress);
    }

    @Override
    public void sendRequestAsync(RpcRequest rpcRequest, long requestId) throws Exception {
        rpcRequest.setRequestId(String.valueOf(requestId));
        InetSocketAddress address = invocationExecutor.resolveServiceAddress(rpcRequest.getServiceName());
        CompletableFuture.runAsync(() -> {
            try {
                sendRequestToAddress(rpcRequest, address);
            } catch (Exception e) {
                log.error("Socket async request failed, requestId={}", requestId, e);
            }
        });
    }

    private RpcResponse sendRequestToAddress(RpcRequest rpcRequest, InetSocketAddress address) throws Exception {
        long requestId = generateRequestId();
        rpcRequest.setRequestId(String.valueOf(requestId));

        // Socket（套接字）版采用“一次请求一个短连接”的实现，结构简单，适合作为最小传输实现。
        try (Socket socket = new Socket()) {
            socket.connect(address, connectTimeout);
            socket.setSoTimeout(resolveReadTimeout(rpcRequest));

            try (DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                 DataInputStream inputStream = new DataInputStream(socket.getInputStream())) {
                RpcMessage requestMessage = buildRequestMessage(rpcRequest, requestId);
                SocketMessageCodec.writeMessage(outputStream, requestMessage);

                RpcMessage responseMessage = SocketMessageCodec.readMessage(inputStream);
                Object body = responseMessage.getBody();
                if (!(body instanceof RpcResponse response)) {
                    throw new RpcException(ErrorCode.SERIALIZATION_ERROR,
                            "Socket response body is not an RpcResponse");
                }
                return response;
            }
        }
    }

    private RpcMessage buildRequestMessage(RpcRequest rpcRequest, long requestId) {
        byte requestSerializerType = resolveSerializerType(rpcRequest);
        // 即使是 socket（套接字）版，也统一走 RpcHeader（消息头）/RpcMessage（协议消息）模型，
        // 保证不同传输实现共享同一套协议语义。
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
        // 方法级配置可以覆盖全局默认序列化器，因此这里按单次请求动态取类型码。
        return (byte) SerializerFactory.getSerializer(serializerName).getSerializerType();
    }

    @Override
    public void close() {
        if (serviceDiscovery != null) {
            serviceDirectory.close();
            serviceDiscovery.close();
        }
    }
}
