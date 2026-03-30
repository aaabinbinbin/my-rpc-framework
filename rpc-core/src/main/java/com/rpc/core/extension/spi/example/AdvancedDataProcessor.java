package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.Initialize;
import com.rpc.core.extension.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 用于演示多个具名注入的示例处理器。
 */
@Slf4j
@Getter
public class AdvancedDataProcessor implements DataProcessor {
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
}
