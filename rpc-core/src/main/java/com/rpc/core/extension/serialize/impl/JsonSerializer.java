package com.rpc.core.extension.serialize.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SerializerType(JsonSerializer.TYPE_JSON)
public class JsonSerializer implements Serializer {
    public static final int TYPE_JSON = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public byte[] serialize(Object obj) {
        try {
            return MAPPER.writeValueAsBytes(obj);
        } catch (Exception e) {
            log.error("JSON serialize failed", e);
            throw new RuntimeException("JSON serialize failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try {
            return MAPPER.readValue(bytes, clazz);
        } catch (Exception e) {
            log.error("JSON deserialize failed", e);
            throw new RuntimeException("JSON deserialize failed", e);
        }
    }

    @Override
    public int getSerializerType() {
        return TYPE_JSON;
    }
}
