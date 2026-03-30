package com.rpc.core.extension.serialize.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Slf4j
@SerializerType(KryoSerializer.TYPE_KRYO)
public class KryoSerializer implements Serializer {
    public static final int TYPE_KRYO = 1;

    private static final ThreadLocal<Kryo> KRYO_LOCAL = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setReferences(true);
        kryo.setRegistrationRequired(false);
        return kryo;
    });

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

    @Override
    public int getSerializerType() {
        return TYPE_KRYO;
    }
}
