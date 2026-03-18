package com.rpc.serialize.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Kryo 序列化器
 */
@Slf4j
public class KryoSerializer implements Serializer {
    public static final int TYPE_KRYO = 1;

    /**
     * 使用 ThreadLocal 保证线程安全
     * Kryo 不是线程安全的，需要每个线程独享一个实例
     */
    private static final ThreadLocal<Kryo> kryoLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        // 配置 Kryo
        kryo.setReferences(true);      // 支持对象循环引用
        kryo.setRegistrationRequired(false);  // 不需要注册类（方便但性能略低）
        
        return kryo;
    });

    @Override
    public byte[] serialize(Object obj) {
        try {
            Output output = new Output(new ByteArrayOutputStream());
            Kryo kryo = kryoLocal.get();
            if (obj == null) {
                // 写入 null 标记
                kryo.writeClassAndObject(output, null);
            } else {
                // 使用 writeClassAndObject 以保存类型信息（支持多态）
                kryo.writeClassAndObject(output, obj);
            }
            return output.toBytes();
        } catch (Exception e) {
            log.error("Kryo 序列化失败", e);
            throw new RuntimeException("Kryo 序列化失败", e);
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
            Kryo kryo = kryoLocal.get();
            // 始终使用 readClassAndObject，与 serialize 方法保持一致
            return (T) kryo.readClassAndObject(input);
        } catch (Exception e) {
            log.error("Kryo 反序列化失败", e);
            throw new RuntimeException("Kryo 反序列化失败", e);
        }
    }

    @Override
    public int getSerializerType() {
        return TYPE_KRYO;
    }
}
