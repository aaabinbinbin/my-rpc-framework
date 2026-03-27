package com.rpc.config;

import com.rpc.registry.RegistryType;
import com.rpc.transport.TransportType;
import lombok.Data;

@Data
public class RpcFrameworkConfig {
    private TransportType transportType = TransportType.NETTY;
    private String serializer = "kryo";
    private String loadBalancer = "random";

    private RegistryType registryType = RegistryType.ZOOKEEPER;
    private String registryAddress = "127.0.0.1:2181";
    private int registryTimeout = 5000;

    private String serverHost = "127.0.0.1";
    private int serverPort = 8080;
    private int bossThreads = 1;
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
    private int shutdownTimeout = 10;
    private int serverReaderIdleTime = 30000;
    private int serverWriterIdleTime = 0;
    private int serverAllIdleTime = 0;

    private int connectTimeout = 5000;
    private int readTimeout = 10000;
    private int heartbeatInterval = 30000;
    private int writerIdleTime = 30000;
    private int readerIdleTime = 10000;
    private int retryTimes = 3;
    private int reconnectMaxRetryTimes = 5;
    private int reconnectInitialDelaySeconds = 2;
    private int reconnectMaxDelaySeconds = 60;
    private boolean enableDegradation = false;
    private int degradationFailureThreshold = 10;
}
