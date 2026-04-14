package com.rpc.core.extension.serialize.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 序列化器实现。
 *
 * 所处阶段：协议编码/解码时作为可读性较好的序列化实现。
 * 主要职责：基于 Jackson ObjectMapper 将对象和 JSON 字节数组互转。
 *
 * 注意事项：JSON 可读性好但体积较大，复杂泛型反序列化能力受 clazz 入参限制。
 */
@Slf4j
@SerializerType(JsonSerializer.TYPE_JSON)
public class JsonSerializer implements Serializer {
    /** 写入 RPC 协议头的 JSON 序列化类型编号。 */
    public static final int TYPE_JSON = 2;

    /** Jackson ObjectMapper 是线程安全的，作为全局单例复用以降低创建成本。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 将对象序列化为 JSON 字节数组。
     */
    @Override
    public byte[] serialize(Object obj) {
        try {
            return MAPPER.writeValueAsBytes(obj);
        } catch (Exception e) {
            log.error("JSON serialize failed", e);
            throw new RuntimeException("JSON serialize failed", e);
        }
    }

    /**
     * 将 JSON 字节数组反序列化为指定类型。
     */
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try {
            return MAPPER.readValue(bytes, clazz);
        } catch (Exception e) {
            log.error("JSON deserialize failed", e);
            throw new RuntimeException("JSON deserialize failed", e);
        }
    }

    /**
     * 返回序列化器类型编号。
     */
    @Override
    public int getSerializerType() {
        return TYPE_JSON;
    }
}
