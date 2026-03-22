package com.rpc.spi.example;

import com.rpc.serialize.Serializer;
import com.rpc.spi.Initialize;
import com.rpc.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认数据处理器
 * 
 * 演示 SPI 依赖注入功能
 */
@Slf4j
@Getter
public class DefaultDataProcessor implements DataProcessor {
    
    /**
     * 注入默认序列化器
     */
    @Inject
    private Serializer serializer;
    
    /**
     * 注入指定名称的序列化器
     */
    @Inject("json")
    private Serializer jsonSerializer;
    
    /**
     * 可选注入，失败不报错
     */
    @Inject(value = "nonexistent", required = false)
    private Serializer optionalSerializer;
    
    /**
     * 标记是否已初始化
     */
    private boolean initialized = false;
    
    @Override
    public String process(String data) {
        log.info("使用序列化器处理数据: {}", serializer.getClass().getSimpleName());
        
        byte[] bytes = serializer.serialize(data);
        String result = serializer.deserialize(bytes, String.class);
        
        return "Processed by " + getName() + ": " + result;
    }
    
    @Override
    public String getName() {
        return "DefaultDataProcessor";
    }
    
    /**
     * 初始化方法
     */
    @Initialize
    public void init() {
        log.info("DefaultDataProcessor 初始化完成, serializer={}", 
            serializer != null ? serializer.getClass().getSimpleName() : "null");
        this.initialized = true;
    }
}