package com.rpc.spi.example;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.serialize.Serializer;
import com.rpc.spi.Initialize;
import com.rpc.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 高级数据处理器
 * 
 * 演示多依赖注入功能
 */
@Slf4j
@Getter
public class AdvancedDataProcessor implements DataProcessor {
    
    /**
     * 注入序列化器
     */
    @Inject("kryo")
    private Serializer serializer;
    
    /**
     * 注入负载均衡器
     */
    @Inject("roundrobin")
    private LoadBalancer loadBalancer;
    
    /**
     * 是否已初始化
     */
    private boolean initialized = false;
    
    /**
     * 处理器配置
     */
    private String config;
    
    @Override
    public String process(String data) {
        log.info("高级处理器处理数据, serializer={}, loadBalancer={}", 
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
    
    /**
     * 初始化方法
     */
    @Initialize
    public void init() {
        this.config = "initialized-config";
        this.initialized = true;
        log.info("AdvancedDataProcessor 初始化完成: serializer={}, loadBalancer={}", 
            serializer.getClass().getSimpleName(),
            loadBalancer.getClass().getSimpleName());
    }
}