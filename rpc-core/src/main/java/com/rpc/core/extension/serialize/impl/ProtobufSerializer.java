package com.rpc.core.extension.serialize.impl;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@SerializerType(ProtobufSerializer.TYPE_PROTOBUF)
public class ProtobufSerializer implements Serializer {
    public static final int TYPE_PROTOBUF = 5;

    private static final ThreadLocal<LinkedBuffer> BUFFER =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    /**
     * RuntimeSchema（运行时模式）查找成本相对较高，因此按类缓存 schema（结构描述）。
     * 这样 protobuf（协议缓冲）序列化的开销就更多集中在真实编码过程本身。
     */
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
            T message = clazz.getDeclaredConstructor().newInstance();
            Schema<T> schema = getSchema(clazz);
            ProtostuffIOUtil.mergeFrom(bytes, message, schema);
            return message;
        } catch (Exception e) {
            log.error("Protobuf deserialize failed", e);
            throw new RuntimeException("Protobuf deserialize failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Schema<T> getSchema(Class<T> clazz) {
        return (Schema<T>) SCHEMA_CACHE.computeIfAbsent(clazz, RuntimeSchema::createFrom);
    }

    @Override
    public int getSerializerType() {
        return TYPE_PROTOBUF;
    }
}
