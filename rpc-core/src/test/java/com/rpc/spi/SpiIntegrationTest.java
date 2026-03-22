package com.rpc.spi;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.loadbalance.factory.LoadBalancerFactory;
import com.rpc.serialize.Serializer;
import com.rpc.serialize.factory.SerializerFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * SPI 功能综合测试
 */
@Slf4j
public class SpiIntegrationTest {
    
    private List<InetSocketAddress> addresses;
    
    @Before
    public void setUp() {
        log.info("========== SPI 功能综合测试开始 ==========");
        
        addresses = new ArrayList<>();
        addresses.add(new InetSocketAddress("192.168.1.1", 8080));
        addresses.add(new InetSocketAddress("192.168.1.2", 8080));
        addresses.add(new InetSocketAddress("192.168.1.3", 8080));
    }
    
    @After
    public void tearDown() {
        log.info("========== SPI 功能综合测试结束 ==========\n");
    }
    
    /**
     * 测试 1：序列化器 SPI 完整功能
     */
    @Test
    public void testSerializerSpi() {
        log.info("\n========== 测试 1：序列化器 SPI 完整功能 ==========");
        
        TestUser user = new TestUser(1L, "张三");
        
        String[] serializerNames = {"kryo", "json", "hessian", "java"};
        
        for (String name : serializerNames) {
            log.info("--- 测试 {} 序列化器 ---", name);
            
            Serializer serializer = SerializerFactory.getSerializer(name);
            assertNotNull(name + " 序列化器不应为空", serializer);
            
            byte[] bytes = serializer.serialize(user);
            assertNotNull("序列化结果不应为空", bytes);
            log.info("{} 序列化后大小: {} bytes", name, bytes.length);
            
            TestUser deserialized = serializer.deserialize(bytes, TestUser.class);
            assertNotNull("反序列化结果不应为空", deserialized);
            assertEquals("ID 应该相等", user.getId(), deserialized.getId());
            assertEquals("名称应该相等", user.getName(), deserialized.getName());
            
            log.info("反序列化结果: {}", deserialized);
        }
        
        log.info("✓ 序列化器 SPI 完整功能测试通过");
    }
    
    /**
     * 测试 2：负载均衡器 SPI 完整功能
     */
    @Test
    public void testLoadBalancerSpi() {
        log.info("\n========== 测试 2：负载均衡器 SPI 完整功能 ==========");
        
        String[] lbNames = {"random", "roundrobin", "consistenthash", "leastconnections"};
        
        for (String name : lbNames) {
            log.info("--- 测试 {} 负载均衡器 ---", name);

            LoadBalancer lb = LoadBalancerFactory.getLoadBalancer(name);
            assertNotNull(name + " 负载均衡器不应为空", lb);
            
            InetSocketAddress selected = lb.select("test-service", addresses);
            assertNotNull("选择结果不应为空", selected);
            assertTrue("选择的地址应该在地址列表中", addresses.contains(selected));
            
            log.info("{} 选择结果: {}", name, selected);
        }
        
        log.info("✓ 负载均衡器 SPI 完整功能测试通过");
    }
    
    /**
     * 测试 3：随机负载均衡分布测试
     */
    @Test
    public void testRandomLoadBalancerDistribution() {
        log.info("\n========== 测试 3：随机负载均衡分布测试 ==========");
        
        LoadBalancer lb = ExtensionFactory.getExtension(LoadBalancer.class, "random");
        Map<String, Integer> distribution = new HashMap<>();
        
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            InetSocketAddress selected = lb.select("test-service", addresses);
            String key = selected.toString();
            distribution.put(key, distribution.getOrDefault(key, 0) + 1);
        }
        
        log.info("随机负载均衡分布:");
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            double percentage = (entry.getValue() * 100.0) / iterations;
            log.info("  {}: {} 次 ({:.2f}%)", entry.getKey(), entry.getValue(), percentage);
        }
        
        log.info("✓ 随机负载均衡分布测试通过");
    }
    
    /**
     * 测试 4：轮询负载均衡顺序测试
     */
    @Test
    public void testRoundRobinLoadBalancerSequence() {
        log.info("\n========== 测试 4：轮询负载均衡顺序测试 ==========");
        
        LoadBalancer lb = ExtensionFactory.getExtension(LoadBalancer.class, "roundrobin");
        
        log.info("轮询顺序:");
        for (int i = 0; i < 10; i++) {
            InetSocketAddress selected = lb.select("test-service", addresses);
            log.info("  第 {} 次选择: {}", i + 1, selected);
        }
        
        log.info("✓ 轮询负载均衡顺序测试通过");
    }
    
    /**
     * 测试 5：序列化性能对比
     */
    @Test
    public void testSerializerPerformance() {
        log.info("\n========== 测试 5：序列化性能对比 ==========");
        
        TestUser user = new TestUser(1L, "张三");
        
        String[] serializerNames = {"kryo", "hessian", "java", "json"};
        int iterations = 100;
        
        for (String name : serializerNames) {
            Serializer serializer = ExtensionFactory.getExtension(Serializer.class, name);
            
            long startTime = System.nanoTime();
            
            for (int i = 0; i < iterations; i++) {
                byte[] bytes = serializer.serialize(user);
                serializer.deserialize(bytes, TestUser.class);
            }
            
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1_000_000;
            
            log.info("{}: {} 次序列化+反序列化耗时 {} ms", name, iterations, duration);
        }
        
        log.info("✓ 序列化性能对比测试完成");
    }
    
    /**
     * 测试 6：动态切换序列化器
     */
    @Test
    public void testSwitchSerializer() {
        log.info("\n========== 测试 6：动态切换序列化器 ==========");
        
        TestUser user = new TestUser(1L, "张三");
        
        Serializer kryoSerializer = ExtensionFactory.getExtension(Serializer.class, "kryo");
        byte[] kryoBytes = kryoSerializer.serialize(user);
        log.info("Kryo 序列化大小: {} bytes", kryoBytes.length);
        
        Serializer jsonSerializer = ExtensionFactory.getExtension(Serializer.class, "json");
        byte[] jsonBytes = jsonSerializer.serialize(user);
        log.info("JSON 序列化大小: {} bytes", jsonBytes.length);
        
        assertNotEquals("不同序列化器产生的字节数应该不同", 
            kryoBytes.length, jsonBytes.length);
        
        log.info("✓ 动态切换序列化器测试通过");
    }
    
    /**
     * 测试 7：动态切换负载均衡器
     */
    @Test
    public void testSwitchLoadBalancer() {
        log.info("\n========== 测试 7：动态切换负载均衡器 ==========");
        
        String serviceName = "test-service";
        
        LoadBalancer randomLb = LoadBalancerFactory.getLoadBalancer("random");
        InetSocketAddress selected1 = randomLb.select(serviceName, addresses);
        log.info("随机负载均衡选择: {}", selected1);
        
        LoadBalancer roundRobinLb = LoadBalancerFactory.getLoadBalancer("roundrobin");
        InetSocketAddress selected2 = roundRobinLb.select(serviceName, addresses);
        log.info("轮询负载均衡选择: {}", selected2);
        
        LoadBalancer hashLb = LoadBalancerFactory.getLoadBalancer("consistenthash");
        InetSocketAddress selected3 = hashLb.select(serviceName, addresses);
        log.info("一致性哈希负载均衡选择: {}", selected3);
        
        log.info("✓ 动态切换负载均衡器测试通过");
    }
    
    /**
     * 测试 8：扩展点验证
     */
    @Test
    public void testExtensionPointValidation() {
        log.info("\n========== 测试 8：扩展点验证 ==========");
        
        assertTrue("Serializer 应该有 @SPI 注解", 
            Serializer.class.isAnnotationPresent(SPI.class));
        assertTrue("LoadBalancer 应该有 @SPI 注解", 
            LoadBalancer.class.isAnnotationPresent(SPI.class));
        
        SPI serializerSpi = Serializer.class.getAnnotation(SPI.class);
        assertEquals("Serializer 默认值应该是 kryo", "kryo", serializerSpi.value());
        
        SPI loadBalancerSpi = LoadBalancer.class.getAnnotation(SPI.class);
        assertEquals("LoadBalancer 默认值应该是 random", "random", loadBalancerSpi.value());
        
        log.info("✓ 扩展点验证测试通过");
    }
    
    /**
     * 测试用户类
     */
    public static class TestUser implements java.io.Serializable {
        private Long id;
        private String name;
        
        public TestUser() {}
        
        public TestUser(Long id, String name) {
            this.id = id;
            this.name = name;
        }
        
        public Long getId() {
            return id;
        }
        
        public void setId(Long id) {
            this.id = id;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        @Override
        public String toString() {
            return "TestUser{id=" + id + ", name='" + name + "'}";
        }
    }
}