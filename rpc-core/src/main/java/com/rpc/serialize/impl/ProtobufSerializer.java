package com.rpc.serialize.impl;

import com.rpc.serialize.Serializer;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ProtobufSerializer implements Serializer {
    public static final int TYPE_PROTOBUF = 5;

    private static final ThreadLocal<LinkedBuffer> BUFFER =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    private static final Map<Class<?>, Schema<?>> SCHEMA_CACHE = new ConcurrentHashMap<>();

    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }

        try {
            @SuppressWarnings("unchecked")
            Class<Object> clazz = (Class<Object>) obj.getClass();
            Schema<Object> schema = getSchema(clazz);
            LinkedBuffer buffer = BUFFER.get();
            try {
                return ProtostuffIOUtil.toByteArray(obj, schema, buffer);
            } finally {
                buffer.clear();
            }
        } catch (Exception e) {
            log.error("Protobuf serialize failed", e);
            throw new RuntimeException("Protobuf serialize failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        try {
            Schema<T> schema = getSchema(clazz);
            T message = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(bytes, message, schema);
            return message;
        } catch (Exception e) {
            log.error("Protobuf deserialize failed", e);
            throw new RuntimeException("Protobuf deserialize failed", e);
        }
    }

    @Override
    public int getSerializerType() {
        return TYPE_PROTOBUF;
    }

    @SuppressWarnings("unchecked")
    private <T> Schema<T> getSchema(Class<T> clazz) {
        return (Schema<T>) SCHEMA_CACHE.computeIfAbsent(clazz, RuntimeSchema::getSchema);
    }
}
