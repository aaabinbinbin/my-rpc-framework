package com.rpc.serialize;

/**
 * 序列化接口
 */
public interface Serializer {
    /**
     * 序列化
     * @param obj 要序列化的对象
     * @return 字节数组
     */
    byte[] serialize(Object obj);

    /**
     * 反序列化
     * @param bytes 字节数组
     * @param clazz 目标类型
     * @return 反序列化后的对象
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz);

    /**
     * 获取序列化器类型标识
     * @return 类型标识（用于协议中标识使用哪种序列化方式）
     */
    int getSerializerType();
}
