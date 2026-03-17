package com.rpc.serialize.impl;

import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Java 原生序列化器（仅供对比测试使用）
 */
@Slf4j
public class JavaSerializer implements Serializer {
    public static final int TYPE_JAVA = 3;

    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {

            oos.writeObject(obj);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Java 原生序列化失败", e);
            throw new RuntimeException("Java 原生序列化失败", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {

            return (T) ois.readObject();
        } catch (Exception e) {
            log.error("Java 原生反序列化失败", e);
            throw new RuntimeException("Java 原生反序列化失败", e);
        }
    }

    @Override
    public int getSerializerType() {
        return TYPE_JAVA;
    }
}
