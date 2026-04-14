package com.rpc.core.extension.serialize.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Kryo 序列化器实现。
 *
 * 所处阶段：协议编码/解码时作为高性能二进制序列化实现。
 * 主要职责：基于 Kryo 将 Java 对象转为紧凑字节数组。
 *
 * 注意事项：Kryo 实例不是线程安全的，因此使用 ThreadLocal 为每个调用线程维护独立实例。
 */
@Slf4j
@SerializerType(KryoSerializer.TYPE_KRYO)
public class KryoSerializer implements Serializer {
    /** 写入 RPC 协议头的 Kryo 序列化类型编号。 */
    public static final int TYPE_KRYO = 1;

    /** 每个线程独享一个 Kryo 实例，避免并发读写内部状态。 */
    private static final ThreadLocal<Kryo> KRYO_LOCAL = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setReferences(true);
        kryo.setRegistrationRequired(false);
        return kryo;
    });

    /**
     * 将对象序列化为 Kryo 字节数组。
     */
    @Override
    public byte[] serialize(Object obj) {
        try {
            Output output = new Output(new ByteArrayOutputStream());
            Kryo kryo = KRYO_LOCAL.get();
            kryo.writeClassAndObject(output, obj);
            return output.toBytes();
        } catch (Exception e) {
            log.error("Kryo serialize failed", e);
            throw new RuntimeException("Kryo serialize failed", e);
        }
    }

    /**
     * 将 Kryo 字节数组反序列化为对象。
     *
     * 边界处理：空字节数组返回 null。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Input input = new Input(new ByteArrayInputStream(bytes));
            return (T) KRYO_LOCAL.get().readClassAndObject(input);
        } catch (Exception e) {
            log.error("Kryo deserialize failed", e);
            throw new RuntimeException("Kryo deserialize failed", e);
        }
    }

    /**
     * 返回序列化器类型编号。
     */
    @Override
    public int getSerializerType() {
        return TYPE_KRYO;
    }
}
