package com.rpc.core.extension.serialize.impl;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

@Slf4j
@SerializerType(JavaSerializer.TYPE_JAVA)
public class JavaSerializer implements Serializer {
    public static final int TYPE_JAVA = 3;

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

    @Override
    public int getSerializerType() {
        return TYPE_JAVA;
    }
}
