package com.rpc.core.extension.serialize;

import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.extension.serialize.impl.HessianSerializer;
import com.rpc.core.extension.serialize.impl.JavaSerializer;
import com.rpc.core.extension.serialize.impl.JsonSerializer;
import com.rpc.core.extension.serialize.impl.KryoSerializer;
import com.rpc.core.extension.serialize.impl.ProtobufSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("测试类：序列化器往返和边界测试")
class SerializerRoundTripTest {
    @DisplayName("验证所有内置序列化器都能完成简单对象往返序列化")
    @Test
    void shouldRoundTripPojoWithAllBuiltInSerializers() {
        SamplePayload payload = new SamplePayload("rpc", 3, new ArrayList<>(List.of("netty", "zookeeper")));

        for (Serializer serializer : builtInSerializers()) {
            byte[] bytes = serializer.serialize(payload);
            SamplePayload decoded = serializer.deserialize(bytes, SamplePayload.class);

            assertEquals(payload, decoded, serializer.getClass().getSimpleName());
        }
    }

    @DisplayName("验证空对象序列化不会破坏反序列化边界")
    @Test
    void shouldHandleNullPayloadConsistently() {
        for (Serializer serializer : builtInSerializers()) {
            byte[] bytes = serializer.serialize(null);
            SamplePayload decoded = serializer.deserialize(bytes, SamplePayload.class);

            assertNotNull(bytes, serializer.getClass().getSimpleName());
            assertNull(decoded, serializer.getClass().getSimpleName());
        }
    }

    @DisplayName("验证非法字节反序列化会快速失败")
    @Test
    void shouldFailFastWhenBytesAreInvalid() {
        byte[] invalidBytes = new byte[]{1, 2, 3, 4};

        for (Serializer serializer : serializersThatRejectInvalidBytes()) {
            assertThrows(RuntimeException.class,
                    () -> serializer.deserialize(invalidBytes, SamplePayload.class),
                    serializer.getClass().getSimpleName());
        }
    }

    @DisplayName("验证序列化器工厂按协议类型码返回正确实现")
    @Test
    void shouldResolveSerializerByProtocolType() {
        assertInstanceOf(KryoSerializer.class, SerializerFactory.getSerializer(KryoSerializer.TYPE_KRYO));
        assertInstanceOf(JsonSerializer.class, SerializerFactory.getSerializer(JsonSerializer.TYPE_JSON));
        assertInstanceOf(JavaSerializer.class, SerializerFactory.getSerializer(JavaSerializer.TYPE_JAVA));
        assertInstanceOf(HessianSerializer.class, SerializerFactory.getSerializer(HessianSerializer.TYPE_HESSIAN));
        assertInstanceOf(ProtobufSerializer.class, SerializerFactory.getSerializer(ProtobufSerializer.TYPE_PROTOBUF));
    }

    @DisplayName("验证未知协议类型码回退到默认序列化器")
    @Test
    void shouldFallbackToDefaultSerializerWhenProtocolTypeUnknown() {
        assertEquals(SerializerFactory.getDefaultSerializer().getClass(),
                SerializerFactory.getSerializer(Integer.MAX_VALUE).getClass());
    }

    @DisplayName("验证同一协议类型码命中缓存后返回同一个序列化器实例")
    @Test
    void shouldCacheSerializerResolvedByProtocolType() {
        Serializer first = SerializerFactory.getSerializer(KryoSerializer.TYPE_KRYO);
        Serializer second = SerializerFactory.getSerializer(KryoSerializer.TYPE_KRYO);

        assertEquals(first, second);
        assertArrayEquals(first.serialize("cache"), second.serialize("cache"));
    }

    private List<Serializer> builtInSerializers() {
        return List.of(
                new KryoSerializer(),
                new JsonSerializer(),
                new JavaSerializer(),
                new HessianSerializer(),
                new ProtobufSerializer()
        );
    }

    private List<Serializer> serializersThatRejectInvalidBytes() {
        return List.of(
                new KryoSerializer(),
                new JsonSerializer(),
                new JavaSerializer(),
                new ProtobufSerializer()
        );
    }

    public static class SamplePayload implements Serializable {
        private String name;
        private int count;
        private List<String> tags;

        public SamplePayload() {
        }

        public SamplePayload(String name, int count, List<String> tags) {
            this.name = name;
            this.count = count;
            this.tags = tags;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SamplePayload that)) {
                return false;
            }
            return count == that.count
                    && java.util.Objects.equals(name, that.name)
                    && java.util.Objects.equals(tags, that.tags);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, count, tags);
        }
    }
}
