package com.rpc.spi;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.loadbalance.factory.LoadBalancerFactory;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * SPI 扩展加载器测试
 */
@Slf4j
public class ExtensionLoaderTest {
    
    @Before
    public void setUp() {
        log.info("========== SPI 测试开始 ==========");
    }
    
    @After
    public void tearDown() {
        log.info("========== SPI 测试结束 ==========\n");
    }
    
    /**
     * 测试 1：加载序列化器扩展
     */
    @Test
    public void testLoadSerializerExtensions() {
        log.info("\n========== 测试 1：加载序列化器扩展 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        Set<String> names = loader.getSupportedExtensions();
        log.info("支持的序列化器: {}", names);
        
        assertNotNull("扩展名称列表不应为空", names);
        assertTrue("应该至少有一个序列化器", names.size() > 0);
        assertTrue("应该包含 kryo", names.contains("kryo"));
        assertTrue("应该包含 json", names.contains("json"));
        assertTrue("应该包含 hessian", names.contains("hessian"));
        assertTrue("应该包含 java", names.contains("java"));
        
        log.info("✓ 成功加载 {} 个序列化器扩展", names.size());
    }
    
    /**
     * 测试 2：根据名称获取扩展实例
     */
    @Test
    public void testGetExtensionByName() {
        log.info("\n========== 测试 2：根据名称获取扩展实例 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        Serializer kryoSerializer = loader.getExtension("kryo");
        assertNotNull("kryo 序列化器不应为空", kryoSerializer);
        assertEquals("类型应该是 1 (KRYO)", 1, kryoSerializer.getSerializerType());
        log.info("kryo 序列化器类型: {}", kryoSerializer.getClass().getSimpleName());
        
        Serializer jsonSerializer = loader.getExtension("json");
        assertNotNull("json 序列化器不应为空", jsonSerializer);
        assertEquals("类型应该是 2 (JSON)", 2, jsonSerializer.getSerializerType());
        log.info("json 序列化器类型: {}", jsonSerializer.getClass().getSimpleName());

        Serializer javaSerializer = loader.getExtension("java");
        assertNotNull("java 序列化器不应为空", javaSerializer);
        assertEquals("类型应该是 3 (java)", 3, javaSerializer.getSerializerType());
        log.info("java 序列化器类型: {}", javaSerializer.getClass().getSimpleName());
        
        Serializer hessianSerializer = loader.getExtension("hessian");
        assertNotNull("hessian 序列化器不应为空", hessianSerializer);
        assertEquals("类型应该是 4 (HESSIAN)", 4, hessianSerializer.getSerializerType());
        log.info("hessian 序列化器类型: {}", hessianSerializer.getClass().getSimpleName());
        
        log.info("✓ 根据名称获取扩展实例成功");
    }
    
    /**
     * 测试 3：获取默认扩展
     */
    @Test
    public void testGetDefaultExtension() {
        log.info("\n========== 测试 3：获取默认扩展 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        Serializer defaultSerializer = loader.getDefaultExtension();
        assertNotNull("默认序列化器不应为空", defaultSerializer);
        
        log.info("默认序列化器: {}", defaultSerializer.getClass().getSimpleName());
        log.info("默认序列化器类型: {}", defaultSerializer.getSerializerType());
        
        log.info("✓ 获取默认扩展成功");
    }
    
    /**
     * 测试 4：单例验证
     */
    @Test
    public void testSingletonInstance() {
        log.info("\n========== 测试 4：单例验证 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        Serializer s1 = loader.getExtension("kryo");
        Serializer s2 = loader.getExtension("kryo");
        
        assertSame("应该是同一个实例", s1, s2);
        log.info("实例 1: {}", s1.hashCode());
        log.info("实例 2: {}", s2.hashCode());
        
        log.info("✓ 单例验证通过");
    }
    
    /**
     * 测试 5：获取所有扩展实例
     */
    @Test
    public void testGetAllExtensions() {
        log.info("\n========== 测试 5：获取所有扩展实例 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        List<Serializer> serializers = loader.getExtensions();
        assertNotNull("序列化器列表不应为空", serializers);
        assertTrue("应该至少有一个序列化器", serializers.size() > 0);
        
        for (Serializer serializer : serializers) {
            log.info("序列化器: {}, 类型: {}", 
                serializer.getClass().getSimpleName(), 
                serializer.getSerializerType());
        }
        
        log.info("✓ 获取所有扩展实例成功");
    }
    
    /**
     * 测试 6：加载负载均衡器扩展
     */
    @Test
    public void testLoadLoadBalancerExtensions() {
        log.info("\n========== 测试 6：加载负载均衡器扩展 ==========");
        
        ExtensionLoader<LoadBalancer> loader = ExtensionLoader.getExtensionLoader(LoadBalancer.class);
        
        Set<String> names = loader.getSupportedExtensions();
        log.info("支持的负载均衡器: {}", names);
        
        assertNotNull("扩展名称列表不应为空", names);
        assertTrue("应该至少有一个负载均衡器", names.size() > 0);
        assertTrue("应该包含 random", names.contains("random"));
        assertTrue("应该包含 roundrobin", names.contains("roundrobin"));
        assertTrue("应该包含 consistenthash", names.contains("consistenthash"));
        assertTrue("应该包含 leastconnections", names.contains("leastconnections"));
        
        for (String name : names) {
            LoadBalancer loadBalancer = loader.getExtension(name);
            log.info("负载均衡器: {}", loadBalancer.getClass().getSimpleName());
        }
        
        log.info("✓ 成功加载 {} 个负载均衡器扩展", names.size());
    }
    
    /**
     * 测试 7：ExtensionFactory 测试
     */
    @Test
    public void testExtensionFactory() {
        log.info("\n========== 测试 7：ExtensionFactory 测试 ==========");
        
        Serializer defaultSerializer = ExtensionFactory.getDefaultExtension(Serializer.class);
        assertNotNull("默认序列化器不应为空", defaultSerializer);
        log.info("默认序列化器: {}", defaultSerializer.getClass().getSimpleName());
        
        Serializer jsonSerializer = ExtensionFactory.getExtension(Serializer.class, "json");
        assertNotNull("json 序列化器不应为空", jsonSerializer);
        log.info("json 序列化器: {}", jsonSerializer.getClass().getSimpleName());
        
        List<Serializer> allSerializers = ExtensionFactory.getExtensions(Serializer.class);
        assertTrue("应该有多个序列化器", allSerializers.size() > 1);
        log.info("序列化器数量: {}", allSerializers.size());
        
        Set<String> names = ExtensionFactory.getSupportedExtensions(Serializer.class);
        assertTrue("应该有多个扩展名称", names.size() > 0);
        log.info("扩展名称: {}", names);
        
        log.info("✓ ExtensionFactory 测试通过");
    }
    
    /**
     * 测试 8：检查扩展是否存在
     */
    @Test
    public void testHasExtension() {
        log.info("\n========== 测试 8：检查扩展是否存在 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        assertTrue("应该存在 kryo", loader.hasExtension("kryo"));
        assertTrue("应该存在 json", loader.hasExtension("json"));
        assertFalse("不应该存在 nonexistent", loader.hasExtension("nonexistent"));
        
        assertTrue("通过工厂检查应该存在 kryo", 
            ExtensionFactory.hasExtension(Serializer.class, "kryo"));
        
        log.info("✓ 检查扩展是否存在测试通过");
    }
    
    /**
     * 测试 9：不存在的扩展
     */
    @Test(expected = IllegalStateException.class)
    public void testNonExistentExtension() {
        log.info("\n========== 测试 9：不存在的扩展 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);

        Serializer nonexistent = loader.getExtension("nonexistent");
        log.info("不存在的扩展: {}", nonexistent.getClass().getSimpleName());
    }
    
    /**
     * 测试 10：负载均衡器默认扩展
     */
    @Test
    public void testLoadBalancerDefaultExtension() {
        log.info("\n========== 测试 10：负载均衡器默认扩展 ==========");
        
        LoadBalancer defaultLb = LoadBalancerFactory.getDefaultLoadBalancer();
        assertNotNull("默认负载均衡器不应为空", defaultLb);
        
        log.info("默认负载均衡器: {}", defaultLb.getClass().getSimpleName());
        log.info("负载均衡器名称: {}", defaultLb.getName());
        
        log.info("✓ 负载均衡器默认扩展测试通过");
    }
}