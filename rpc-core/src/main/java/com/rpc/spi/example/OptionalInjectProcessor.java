package com.rpc.spi.example;

import com.rpc.serialize.Serializer;
import com.rpc.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 可选注入测试处理器
 * 
 * 专门测试 required=false 的场景
 */
@Slf4j
@Getter
public class OptionalInjectProcessor implements DataProcessor {
    
    /**
     * 必须注入存在的扩展
     */
    @Inject("kryo")
    private Serializer requiredSerializer;
    
    /**
     * 可选注入不存在的扩展 - 应该不会抛出异常
     */
    @Inject(value = "nonexistent_serializer", required = false)
    private Serializer optionalNonexistentSerializer;
    
    /**
     * 可选注入存在的扩展 - 应该正常注入
     */
    @Inject(value = "json", required = false)
    private Serializer optionalExistingSerializer;
    
    /**
     * 默认注入 - 应该正常注入
     */
    @Inject
    private Serializer defaultSerializer;
    
    @Override
    public String process(String data) {
        log.info("处理数据: requiredSerializer={}, optionalNonexistentSerializer={}, optionalExistingSerializer={}, defaultSerializer={}",
            requiredSerializer != null ? requiredSerializer.getClass().getSimpleName() : "null",
            optionalNonexistentSerializer != null ? optionalNonexistentSerializer.getClass().getSimpleName() : "null",
            optionalExistingSerializer != null ? optionalExistingSerializer.getClass().getSimpleName() : "null",
            defaultSerializer != null ? defaultSerializer.getClass().getSimpleName() : "null");
        
        return "Processed by OptionalInjectProcessor";
    }
    
    @Override
    public String getName() {
        return "OptionalInjectProcessor";
    }
}