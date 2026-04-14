package com.rpc.core.extension.serialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 序列化器类型编号注解。
 *
 * 所处阶段：SerializerFactory 扫描 SPI 扩展时读取，用于建立 serializerType -> Serializer 的映射。
 * 主要职责：让协议头中的序列化类型字节能够反查到具体序列化器实现。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SerializerType {
    /**
     * 序列化器类型编号。
     *
     * 注意事项：编号需要保持稳定，否则新旧客户端/服务端之间可能无法正确反序列化。
     */
    int value();
}
