package com.rpc.core.extension.serialize;

import com.rpc.core.extension.spi.SPI;

/**
 * 协议编解码使用的序列化 SPI（可插拔扩展点）。
 * 序列化器类型码会写入协议头，这样发送端和接收端就能协商具体序列化器，
 * 而不需要在传输层代码里写死。
 */
@SPI("protobuf")
public interface Serializer {
    byte[] serialize(Object obj);

    <T> T deserialize(byte[] bytes, Class<T> clazz);

    int getSerializerType();
}
