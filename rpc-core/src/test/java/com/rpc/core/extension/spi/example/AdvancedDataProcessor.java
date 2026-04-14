package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.Initialize;
import com.rpc.core.extension.spi.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于演示多个具名注入的示例处理器。
 */
public class AdvancedDataProcessor implements DataProcessor {
    private static final Logger log = LoggerFactory.getLogger(AdvancedDataProcessor.class);

    @Inject("kryo")
    private Serializer serializer;

    @Inject("roundrobin")
    private LoadBalancer loadBalancer;

    private boolean initialized = false;
    private String config;

    @Override
    public String process(String data) {
        log.info("Advanced processor uses serializer={}, loadBalancer={}",
                serializer.getClass().getSimpleName(),
                loadBalancer.getClass().getSimpleName());

        byte[] bytes = serializer.serialize(data);
        String result = serializer.deserialize(bytes, String.class);
        return "Processed by " + getName() + " [config=" + config + "]: " + result;
    }

    @Override
    public String getName() {
        return "AdvancedDataProcessor";
    }

    @Initialize
    public void init() {
        this.config = "initialized-config";
        this.initialized = true;
        log.info("AdvancedDataProcessor initialized, serializer={}, loadBalancer={}",
                serializer.getClass().getSimpleName(),
                loadBalancer.getClass().getSimpleName());
    }

    public Serializer getSerializer() {
        return serializer;
    }

    public LoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getConfig() {
        return config;
    }
}
