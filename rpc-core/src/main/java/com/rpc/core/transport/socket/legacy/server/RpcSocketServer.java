package com.rpc.core.transport.socket.legacy.server;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.local.LocalRegistryImpl;
import com.rpc.core.runtime.server.BizThreadPool;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.config.server.RpcServerConfig;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestDispatcher;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestExecutor;
import com.rpc.core.transport.server.RpcRequestProcessor;
import com.rpc.core.transport.socket.legacy.SocketMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * legacy JDK Socket 服务端实现。
 *
 * 所处阶段：RpcServerFactory 根据 SOCKET 传输类型创建 provider 服务端时。
 * 主要职责：监听 TCP 端口、接收 Socket 请求、委托统一 RpcRequestProcessor 执行业务，并写回响应。
 *
 * 注意事项：该实现主要用于兼容和对比测试，高并发主路径建议使用 RpcNettyServer。
 */
@Slf4j
public class RpcSocketServer implements RpcServer {
    /** 服务端配置。 */
    private final RpcServerConfig config;
    /** 本地服务注册表，负责接口名到服务对象的映射。 */
    private final LocalRegistry localRegistry;
    /** 统一请求处理器，复用 Netty 服务端的分发和执行逻辑。 */
    private final RpcRequestProcessor requestProcessor;
    /** Socket accept 后处理连接的工作线程池。 */
    private final ExecutorService acceptWorkerPool;
    /** 执行业务请求的线程池，防止 IO 接收线程直接执行业务。 */
    private final ExecutorService bizExecutor;
    /** 服务端生命周期控制器，用于优雅停机和 inflight 请求统计。 */
    private final ServerLifecycle serverLifecycle;
    /** 服务端运行标记。 */
    private volatile boolean running;
    /** JDK ServerSocket。 */
    private ServerSocket serverSocket;
    /** 防止重复执行 shutdown。 */
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    /**
     * 创建 legacy Socket 服务端。
     */
    public RpcSocketServer(RpcServerConfig config, ServiceRegistry registry) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl(registry, config.getHost(), config.getPort());
        this.serverLifecycle = new ServerLifecycle();
        this.acceptWorkerPool = BizThreadPool.create(
                config.getWorkerThreads(),
                config.getWorkerThreads(),
                config.getBizQueueCapacity()
        );
        this.bizExecutor = BizThreadPool.create(
                config.getBizCoreThreads(),
                config.getBizMaxThreads(),
                config.getBizQueueCapacity()
        );
        this.requestProcessor = new RpcRequestDispatcher(
                new RpcRequestExecutor(localRegistry, serverLifecycle),
                serverLifecycle
        );
    }

    /**
     * 启动 Socket 服务端并进入 accept 循环。
     *
     * 边界处理：start 退出时必定调用 shutdown，保证服务注销和线程池释放。
     */
    @Override
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new java.net.InetSocketAddress(config.getHost(), config.getPort()));
        running = true;

        try {
            while (running) {
                Socket socket = serverSocket.accept();
                acceptWorkerPool.submit(() -> handleConnection(socket));
            }
        } finally {
            shutdown();
        }
    }

    /**
     * 处理单条 Socket 连接。
     *
     * 注意事项：当前 legacy 实现一次连接处理一条请求，处理完即关闭连接。
     */
    private void handleConnection(Socket socket) {
        try (Socket client = socket;
             DataInputStream inputStream = new DataInputStream(client.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(client.getOutputStream())) {
            RpcMessage request = SocketMessageCodec.readMessage(inputStream);
            RpcMessage response = executeRequest(request);
            if (response != null) {
                SocketMessageCodec.writeMessage(outputStream, response);
            }
        } catch (Exception e) {
            if (e instanceof EOFException) {
                log.debug("Socket connection closed before a complete RPC message was received");
                return;
            }
            log.error("Failed to handle socket request", e);
        }
    }

    /**
     * 在线程池中执行业务请求。
     *
     * 边界处理：业务线程池拒绝任务时返回 SERVER_BUSY 响应，而不是直接断开连接。
     */
    private RpcMessage executeRequest(RpcMessage request) throws ExecutionException, InterruptedException {
        try {
            return bizExecutor.submit(() -> requestProcessor.process(request)).get();
        } catch (RejectedExecutionException e) {
            log.warn("Socket biz executor is saturated, return busy response");
            return buildBusyResponse(request);
        }
    }

    /**
     * 构造服务端繁忙响应。
     *
     * 边界处理：请求消息或协议头为空时无法构造合法响应，返回 null。
     */
    private RpcMessage buildBusyResponse(RpcMessage requestMessage) {
        if (requestMessage == null || requestMessage.getHeader() == null) {
            return null;
        }

        String requestId = requestMessage.getBody() instanceof RpcRequest rpcRequest
                ? rpcRequest.getRequestId()
                : String.valueOf(requestMessage.getHeader().getRequestId());

        RpcMessage response = new RpcMessage();
        response.setHeader(RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(requestMessage.getHeader().getSerializerType())
                .messageType(RpcMessageType.RESPONSE.getCode())
                .reserved((byte) 0)
                .requestId(requestMessage.getHeader().getRequestId())
                .build());
        response.setBody(RpcResponse.fail(
                ErrorCode.SERVER_BUSY.getCode(),
                ErrorCode.SERVER_BUSY.getDescription(),
                requestId
        ));
        return response;
    }

    /**
     * 关闭 Socket 服务端并释放资源。
     *
     * 注意事项：方法幂等；先停止接收新请求，再等待 inflight 请求排空，最后注销服务和关闭线程池。
     */
    @Override
    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        running = false;
        serverLifecycle.stopAcceptingRequests();

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.warn("Failed to close socket server", e);
            }
        }

        serverLifecycle.awaitDrained(config.getShutdownTimeout(), TimeUnit.SECONDS);
        unregisterAllServices();
        shutdownExecutor(acceptWorkerPool);
        shutdownExecutor(bizExecutor);
    }

    /**
     * 获取本地服务注册表。
     */
    @Override
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }

    /**
     * 优雅关闭线程池，超时后强制关闭。
     */
    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.getShutdownTimeout(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 注销当前服务端已注册的所有服务。
     */
    private void unregisterAllServices() {
        for (String serviceName : localRegistry.serviceNames()) {
            try {
                localRegistry.unregister(serviceName);
            } catch (Exception e) {
                log.warn("Failed to unregister service during shutdown: {}", serviceName, e);
            }
        }
    }
}
