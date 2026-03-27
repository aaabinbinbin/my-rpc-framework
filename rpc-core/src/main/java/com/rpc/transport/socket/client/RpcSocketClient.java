package com.rpc.transport.socket.client;

import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;
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
import com.rpc.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.transport.socket.SocketMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class RpcSocketClient implements RpcTransport {
    private final ServiceRegistry serviceRegistry;
    private final int connectTimeout;
    private final int readTimeout;
    private final RpcClientInvocationExecutor invocationExecutor;

    public RpcSocketClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.connectTimeout = config.getConnectTimeout();
        this.readTimeout = config.getReadTimeout();

        LoadBalancer loadBalancer = config.getLoadBalancer();
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        RetryExecutor retryExecutor = new RetryExecutor(new DefaultRetryStrategy(), config.getRetryTimes());
        DegradationPolicy degradationPolicy = config.getDegradationPolicy();

        RpcServiceResolver serviceResolver = new RpcServiceResolver(serviceRegistry, loadBalancer, circuitBreakerManager);
        this.invocationExecutor = new RpcClientInvocationExecutor(
                serviceResolver,
                circuitBreakerManager,
                retryExecutor,
                degradationPolicy,
                config.isEnableDegradation(),
                config.getDegradationFailureThreshold()
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
                log.error("Socket 异步请求发送失败: requestId={}", requestId, e);
            }
        });
    }

    private RpcResponse sendRequestToAddress(RpcRequest rpcRequest, InetSocketAddress address) throws Exception {
        long requestId = generateRequestId();
        rpcRequest.setRequestId(String.valueOf(requestId));

        try (Socket socket = new Socket()) {
            socket.connect(address, connectTimeout);
            socket.setSoTimeout(readTimeout);

            try (DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                 DataInputStream inputStream = new DataInputStream(socket.getInputStream())) {
                RpcMessage requestMessage = buildRequestMessage(rpcRequest, requestId);
                SocketMessageCodec.writeMessage(outputStream, requestMessage);

                RpcMessage responseMessage = SocketMessageCodec.readMessage(inputStream);
                Object body = responseMessage.getBody();
                if (!(body instanceof RpcResponse response)) {
                    throw new RpcException(ErrorCode.SERIALIZATION_ERROR, "Socket 响应体类型错误");
                }
                return response;
            }
        }
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

    @Override
    public void close() {
        if (serviceRegistry != null) {
            serviceRegistry.close();
        }
    }
}
