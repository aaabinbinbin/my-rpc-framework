package com.rpc.core.extension.serialize.impl;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.SerializerType;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Hessian2 序列化器实现。
 *
 * 所处阶段：协议编码前把请求/响应体转为字节数组，协议解码后把字节数组恢复为对象。
 * 主要职责：提供跨语言友好的二进制序列化能力。
 *
 * 边界处理：序列化 null 时返回空字节数组，反序列化空字节数组时返回 null。
 */
@Slf4j
@SerializerType(HessianSerializer.TYPE_HESSIAN)
public class HessianSerializer implements Serializer {
    /** 写入 RPC 协议头的 Hessian 序列化类型编号。 */
    public static final int TYPE_HESSIAN = 4;

    /**
     * 将对象序列化为 Hessian2 字节数组。
     */
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

    /**
     * 将 Hessian2 字节数组反序列化为对象。
     */
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

    /**
     * 返回序列化器类型编号。
     */
    @Override
    public int getSerializerType() {
        return TYPE_HESSIAN;
    }
}
