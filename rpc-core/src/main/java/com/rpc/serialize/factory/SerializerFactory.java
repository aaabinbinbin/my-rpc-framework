package com.rpc.serialize.factory;

import com.rpc.serialize.Serializer;
import com.rpc.serialize.impl.HessianSerializer;
import com.rpc.serialize.impl.JavaSerializer;
import com.rpc.serialize.impl.JsonSerializer;
import com.rpc.serialize.impl.KryoSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.HashMap;
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
        // 注册各种序列化器
        SERIALIZER_MAP.put(KryoSerializer.TYPE_KRYO, new KryoSerializer());
        SERIALIZER_MAP.put(JsonSerializer.TYPE_JSON, new JsonSerializer());
        SERIALIZER_MAP.put(JavaSerializer.TYPE_JAVA, new JavaSerializer());
        SERIALIZER_MAP.put(HessianSerializer.TYPE_HESSIAN, new HessianSerializer());

        // 从配置文件加载默认序列化器
        DEFAULT_SERIALIZER = loadDefaultSerializer();
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

    private static Serializer loadDefaultSerializer() {
        try {
            // 从 classpath 加载 rpc.properties
            Properties props = new Properties();
            InputStream is = SerializerFactory.class.getClassLoader()
                    .getResourceAsStream("rpc.properties");

            if (is != null) {
                props.load(is);
                String typeName = props.getProperty("rpc.serializer.type", "kryo");
                return getSerializerByName(typeName);
            }
        } catch (Exception e) {
            // 如果加载失败，使用 Kryo 作为默认
            System.err.println("加载序列化器配置失败，使用默认 Kryo: " + e.getMessage());
        }
        return new KryoSerializer();
    }

    private static Serializer getSerializerByName(String name) {
        switch (name.toLowerCase().trim()) {
            case "kryo":
                return new KryoSerializer();
            case "json":
                return new JsonSerializer();
            case "hessian":
                return new HessianSerializer();
            case "java":
                return new JavaSerializer();
            default:
                System.err.println("未识别的序列化器：" + name + "，使用 Kryo");
                return new KryoSerializer();
        }
    }
}
