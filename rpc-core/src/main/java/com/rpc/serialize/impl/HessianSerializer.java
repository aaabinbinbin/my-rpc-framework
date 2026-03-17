package com.rpc.serialize.impl;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Hessian 序列化器
 */
@Slf4j
public class HessianSerializer implements Serializer {
    public static final int TYPE_HESSIAN = 4;
    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Hessian2Output output = new Hessian2Output(baos);
            output.writeObject(obj);
            output.flush();
            return baos.toByteArray();
        } catch(Exception e) {
            log.error("Hessian 序列化失败", e);
            throw new RuntimeException("Hessian 序列化失败", e);
        }

    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            Hessian2Input input = new Hessian2Input(bais);
            return (T) input.readObject();
        } catch (IOException e) {
            log.error("Hessian 反序列化失败", e);
            throw new RuntimeException("Hessian 反序列化失败", e);
        }
    }

    @Override
    public int getSerializerType() {
        return TYPE_HESSIAN;
    }
}
