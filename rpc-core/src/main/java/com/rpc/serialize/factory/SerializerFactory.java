package com.rpc.serialize.factory;

import com.rpc.serialize.Serializer;
import com.rpc.serialize.impl.HessianSerializer;
import com.rpc.serialize.impl.JavaSerializer;
import com.rpc.serialize.impl.JsonSerializer;
import com.rpc.serialize.impl.KryoSerializer;
import com.rpc.spi.ExtensionFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 序列化器工厂
 */
@Slf4j
public class SerializerFactory {
    /** 存储所有可用的序列化器 */
    private static final Map<Integer, Serializer> SERIALIZER_MAP = new HashMap<>();

    /** 默认序列化器 */
    public static final Serializer DEFAULT_SERIALIZER;

    static {
        // 使用 SPI 加载所有序列化器
        List<Serializer> serializerList = ExtensionFactory.getExtensions(Serializer.class);
        for (Serializer serializer : serializerList) {
            SERIALIZER_MAP.put(serializer.getSerializerType(), serializer);
            log.info("加载序列化器: {} -> {}",
                    serializer.getSerializerType(),
                    serializer.getClass().getSimpleName());
        }

        // 获取默认序列化器
        DEFAULT_SERIALIZER = ExtensionFactory.getDefaultExtension(Serializer.class);
        log.info("默认序列化器: {}", DEFAULT_SERIALIZER.getClass().getSimpleName());
    }

    /**
     * 根据类型获取序列化器
     * @param type 序列化器类型标识
     * @return 序列化器实例
     */
    public static Serializer getSerializer(int type) {
        return SERIALIZER_MAP.getOrDefault(type, DEFAULT_SERIALIZER);
    }

    /**
     * 获取默认序列化器
     */
    public static Serializer getDefaultSerializer() {
        return DEFAULT_SERIALIZER;
    }

    /**
     * 根据名称获取序列化器
     */
    public static Serializer getSerializer(String name) {
        return ExtensionFactory.getExtension(Serializer.class, name);
    }
}
