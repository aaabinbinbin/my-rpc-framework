package com.rpc.core.extension.serialize.impl;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
@SerializerType(HessianSerializer.TYPE_HESSIAN)
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
        } catch (Exception e) {
            log.error("Hessian serialize failed", e);
            throw new RuntimeException("Hessian serialize failed", e);
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
            log.error("Hessian deserialize failed", e);
            throw new RuntimeException("Hessian deserialize failed", e);
        }
    }

    @Override
    public int getSerializerType() {
        return TYPE_HESSIAN;
    }
}
