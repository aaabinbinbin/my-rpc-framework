package com.rpc.core.extension.serialize.factory;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import com.rpc.core.extension.spi.ExtensionFactory;
import com.rpc.core.extension.spi.ExtensionLoader;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序列化扩展工厂。
 */
@Slf4j
public class SerializerFactory {
    private static final Map<Integer, String> SERIALIZER_NAME_BY_TYPE = new ConcurrentHashMap<>();
    private static final Map<Integer, Serializer> SERIALIZER_CACHE_BY_TYPE = new ConcurrentHashMap<>();

    static {
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        Set<String> names = loader.getSupportedExtensions();
        for (String name : names) {
            Class<?> clazz = loader.getExtensionClass(name);
            SerializerType serializerType = clazz.getAnnotation(SerializerType.class);
            if (serializerType == null) {
                log.warn("Serializer {} has no @SerializerType annotation", clazz.getName());
                continue;
            }
    // 这里只根据类元数据建立映射，
    // 启动时不需要为了拿到类型码就把所有序列化器实现都提前实例化。
            SERIALIZER_NAME_BY_TYPE.put(serializerType.value(), name);
        }
    }

    public static Serializer getSerializer(int type) {
    // 协议解码阶段通常先拿到的是数值类型码，
    // 因此单独维护 serializerType（序列化类型） -> serializer（序列化器）实例的缓存来覆盖这条热点路径。
        String name = SERIALIZER_NAME_BY_TYPE.get(type);
        if (name == null) {
            return getDefaultSerializer();
        }
        return SERIALIZER_CACHE_BY_TYPE.computeIfAbsent(type, ignored -> getSerializer(name));
    }

    public static Serializer getDefaultSerializer() {
        return ExtensionFactory.getDefaultExtension(Serializer.class);
    }

    public static Serializer getSerializer(String name) {
        return ExtensionFactory.getExtension(Serializer.class, name);
    }
}
