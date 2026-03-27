package com.rpc.transport.socket.server;

import com.rpc.protocol.RpcMessage;
import com.rpc.registry.LocalRegistry;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.LocalRegistryImpl;
import com.rpc.transport.RpcServer;
import com.rpc.transport.netty.server.config.RpcServerConfig;
import com.rpc.transport.netty.server.dispatch.RpcRequestDispatcher;
import com.rpc.transport.netty.server.dispatch.RpcRequestExecutor;
import com.rpc.transport.server.RpcRequestProcessor;
import com.rpc.transport.socket.SocketMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RpcSocketServer implements RpcServer {
    private final RpcServerConfig config;
    private final LocalRegistry localRegistry;
    private final RpcRequestProcessor requestProcessor;
    private final ExecutorService workerPool;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public RpcSocketServer(RpcServerConfig config, ServiceRegistry registry) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl(registry, config.getHost(), config.getPort());
        this.requestProcessor = new RpcRequestDispatcher(new RpcRequestExecutor(localRegistry));
        this.workerPool = Executors.newFixedThreadPool(config.getWorkerThreads());
    }

    @Override
    public void start() throws IOException {
        serverSocket = new ServerSocket(config.getPort());
        running = true;

        log.info("========================================");
        log.info("RPC Socket 服务端启动成功");
        log.info("监听端口: {}", config.getPort());
        log.info("Worker 线程数: {}", config.getWorkerThreads());
        log.info("========================================");

        try {
            while (running) {
                Socket socket = serverSocket.accept();
                workerPool.submit(() -> handleConnection(socket));
            }
        } finally {
            shutdown();
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket client = socket;
             DataInputStream inputStream = new DataInputStream(client.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(client.getOutputStream())) {
            RpcMessage request = SocketMessageCodec.readMessage(inputStream);
            RpcMessage response = requestProcessor.process(request);
            if (response != null) {
                SocketMessageCodec.writeMessage(outputStream, response);
            }
        } catch (Exception e) {
            log.error("处理 Socket 请求失败", e);
        }
    }

    @Override
    public void shutdown() {
        running = false;

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.warn("关闭 Socket 服务端失败", e);
            }
        }

        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(config.getShutdownTimeout(), TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }
}
