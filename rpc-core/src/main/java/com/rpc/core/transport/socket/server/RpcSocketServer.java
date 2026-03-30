package com.rpc.core.transport.socket.server;

import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.impl.LocalRegistryImpl;
import com.rpc.core.runtime.server.BizThreadPool;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.netty.server.config.RpcServerConfig;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestDispatcher;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestExecutor;
import com.rpc.core.transport.server.RpcRequestProcessor;
import com.rpc.core.transport.socket.SocketMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RpcSocketServer implements RpcServer {
    /**
     * Socket（套接字）服务端保留与 Netty（网络通信框架）服务端一致的服务端分层：
     * 本地注册表、请求分发、业务线程池、优雅停机。
     * 区别只在于底层 IO（输入输出）使用阻塞式 ServerSocket（服务端套接字）。
     */
    private final RpcServerConfig config;
    private final LocalRegistry localRegistry;
    private final RpcRequestProcessor requestProcessor;
    private final ExecutorService acceptWorkerPool;
    private final ExecutorService bizExecutor;
    private final ServerLifecycle serverLifecycle;
    private volatile boolean running;
    private ServerSocket serverSocket;

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
                new RpcRequestExecutor(localRegistry, bizExecutor, serverLifecycle),
                serverLifecycle
        );
    }

    @Override
    public void start() throws IOException {
        serverSocket = new ServerSocket(config.getPort());
        running = true;

        try {
            while (running) {
                // accept（接入）线程只负责接入连接，具体处理仍然交给工作线程，
                // 避免阻塞式 IO（输入输出）让主循环无法继续接收新连接。
                Socket socket = serverSocket.accept();
                acceptWorkerPool.submit(() -> handleConnection(socket));
            }
        } finally {
            shutdown();
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket client = socket;
             DataInputStream inputStream = new DataInputStream(client.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(client.getOutputStream())) {
            // Socket（套接字）版当前按“一连接一请求”处理，读到请求后立即分发执行业务并回写响应。
            RpcMessage request = SocketMessageCodec.readMessage(inputStream);
            RpcMessage response = requestProcessor.process(request);
            if (response != null) {
                SocketMessageCodec.writeMessage(outputStream, response);
            }
        } catch (Exception e) {
            log.error("Failed to handle socket request", e);
        }
    }

    @Override
    public void shutdown() {
        running = false;
        // 停机顺序与 Netty（网络通信框架）版保持一致：先拒绝新请求，再摘注册，最后等待存量任务 drain（排空）。
        serverLifecycle.stopAcceptingRequests();
        unregisterAllServices();
        serverLifecycle.awaitDrained(config.getShutdownTimeout(), TimeUnit.SECONDS);

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.warn("Failed to close socket server", e);
            }
        }

        shutdownExecutor(acceptWorkerPool);
        shutdownExecutor(bizExecutor);
    }

    @Override
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }

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
