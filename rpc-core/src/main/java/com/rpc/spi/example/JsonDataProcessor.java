package com.rpc.spi.example;

import com.rpc.serialize.Serializer;
import com.rpc.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 数据处理器
 * 
 * 使用 JSON 序列化器处理数据
 */
@Slf4j
@Getter
public class JsonDataProcessor implements DataProcessor {
    
    /**
     * 注入 JSON 序列化器
     */
    @Inject("json")
    private Serializer jsonSerializer;
    
    @Override
    public String process(String data) {
        log.info("使用 JSON 序列化器处理数据");
        
        byte[] bytes = jsonSerializer.serialize(data);
        String result = jsonSerializer.deserialize(bytes, String.class);
        
        return "Processed by " + getName() + ": " + result;
    }
    
    @Override
    public String getName() {
        return "JsonDataProcessor";
    }
}