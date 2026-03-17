package com.rpc.serialize.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 序列化器（Jackson 实现）
 */
@Slf4j
public class JsonSerializer implements Serializer {
    public static final int TYPE_JSON = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public byte[] serialize(Object obj) {
        try {
            return MAPPER.writeValueAsBytes(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try {
            return MAPPER.readValue(bytes, clazz);
        } catch (Exception e) {
            log.error("JSON 反序列化失败", e);
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }

    @Override
    public int getSerializerType() {
        return TYPE_JSON;
    }
}
