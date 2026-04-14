package com.rpc.core.extension.serialize.impl;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * JDK 原生序列化器实现。
 *
 * 所处阶段：协议编码/解码时作为一种可选序列化实现。
 * 主要职责：提供零额外协议适配的 Java 对象序列化能力。
 *
 * 注意事项：JDK 序列化性能和安全性都不是高并发生产首选，通常用于兼容、测试或简单内部场景。
 */
@Slf4j
@SerializerType(JavaSerializer.TYPE_JAVA)
public class JavaSerializer implements Serializer {
    /** 写入 RPC 协议头的 Java 序列化类型编号。 */
    public static final int TYPE_JAVA = 3;

    /**
     * 将对象序列化为 JDK ObjectOutputStream 字节数组。
     *
     * 边界处理：对象必须实现 Serializable，否则会抛出序列化异常。
     */
    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Java serialize failed", e);
            throw new RuntimeException("Java serialize failed", e);
        }
    }

    /**
     * 将 JDK 序列化字节数组反序列化为对象。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (T) ois.readObject();
        } catch (Exception e) {
            log.error("Java deserialize failed", e);
            throw new RuntimeException("Java deserialize failed", e);
        }
    }

    /**
     * 返回序列化器类型编号。
     */
    @Override
    public int getSerializerType() {
        return TYPE_JAVA;
    }
}
