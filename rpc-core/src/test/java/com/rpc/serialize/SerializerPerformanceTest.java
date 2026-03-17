package com.rpc.serialize;

import com.rpc.model.User;
import com.rpc.serialize.impl.HessianSerializer;
import com.rpc.serialize.impl.JavaSerializer;
import com.rpc.serialize.impl.JsonSerializer;
import com.rpc.serialize.impl.KryoSerializer;
import org.junit.Test;
import lombok.extern.slf4j.Slf4j;

import static org.junit.Assert.*;

@Slf4j
public class SerializerPerformanceTest {

    public static void main(String[] args) {
        User user = new User("张三", 25, "zhangsan@example.com");
        int iterations = 10000;

        System.out.println("=== 序列化性能测试 ===\n");

        // 测试 Kryo
        testSerializer("Kryo", new KryoSerializer(), user, iterations);

        // 测试 JSON
        testSerializer("JSON", new JsonSerializer(), user, iterations);

        // 测试 Java 原生
        testSerializer("Java 原生", new JavaSerializer(), user, iterations);

        // 测试 Java 原生
        testSerializer("Hessian 原生", new HessianSerializer(), user, iterations);
    }

    private static void testSerializer(String name, Serializer serializer,
                                       User user, int iterations) {
        try {
            // 预热
            for (int i = 0; i < 100; i++) {
                byte[] bytes = serializer.serialize(user);
                serializer.deserialize(bytes, User.class);
            }

            // 正式测试
            long startSerialize = System.currentTimeMillis();
            byte[] bytes = null;
            for (int i = 0; i < iterations; i++) {
                bytes = serializer.serialize(user);
            }
            long endSerialize = System.currentTimeMillis();

            long startDeserialize = System.currentTimeMillis();
            for (int i = 0; i < iterations; i++) {
                serializer.deserialize(bytes, User.class);
            }
            long endDeserialize = System.currentTimeMillis();

            System.out.printf("%s:\n", name);
            System.out.printf("  序列化后大小：%d 字节\n", bytes.length);
            System.out.printf("  序列化耗时：%d ms\n", endSerialize - startSerialize);
            System.out.printf("  反序列化耗时：%d ms\n\n",
                    endDeserialize - startDeserialize);

        } catch (Exception e) {
            System.err.println(name + " 测试失败：" + e.getMessage());
        }
    }

    @Test
    public void testNullSerialization() {
        KryoSerializer serializer = new KryoSerializer();

        // 测试 null 值序列化
        byte[] bytes = serializer.serialize(null);
        Object result = serializer.deserialize(bytes, Object.class);

        assertNull(result);  // 应该返回 null
    }

    @Test
    public void testNormalSerialization() {
        KryoSerializer serializer = new KryoSerializer();
        User user = new User("张三", 25, "zhangsan@example.com");

        byte[] bytes = serializer.serialize(user);
        User result = serializer.deserialize(bytes, User.class);
        log.info("正常序列化输出: {}", result);
    }
}
