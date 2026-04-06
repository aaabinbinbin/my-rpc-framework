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
 * 序列化器工厂。
 *
 * 这个工厂解决两个问题：
 * 1. 配置阶段通常按名称获取序列化器，例如 protobuf、json。
 * 2. 协议解码阶段通常按 serializerType 类型码获取序列化器。
 *
 * 因此这里同时维护“名称 -> 实现”和“类型码 -> 实现”的桥接逻辑。
 */
@Slf4j
public class SerializerFactory {
    /** 序列化类型码到扩展名的映射。 */
    private static final Map<Integer, String> SERIALIZER_NAME_BY_TYPE = new ConcurrentHashMap<>();
    /** 按类型码缓存序列化器实例。 */
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
            SERIALIZER_NAME_BY_TYPE.put(serializerType.value(), name);
        }
    }

    /**
     * 按协议头中的类型码获取序列化器。
     *
     * 这是编解码阶段的热点路径，因此这里单独做了一层类型码缓存。
     */
    public static Serializer getSerializer(int type) {
        String name = SERIALIZER_NAME_BY_TYPE.get(type);
        if (name == null) {
            return getDefaultSerializer();
        }
        return SERIALIZER_CACHE_BY_TYPE.computeIfAbsent(type, ignored -> getSerializer(name));
    }

    /** 获取默认序列化器。 */
    public static Serializer getDefaultSerializer() {
        return ExtensionFactory.getDefaultExtension(Serializer.class);
    }

    /** 按扩展名获取序列化器。 */
    public static Serializer getSerializer(String name) {
        return ExtensionFactory.getExtension(Serializer.class, name);
    }
}
