package com.rpc.spi;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.serialize.Serializer;
import com.rpc.spi.example.AdvancedDataProcessor;
import com.rpc.spi.example.DataProcessor;
import com.rpc.spi.example.DefaultDataProcessor;
import com.rpc.spi.example.JsonDataProcessor;
import com.rpc.spi.example.OptionalInjectProcessor;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SPI 依赖注入功能测试
 */
@Slf4j
public class SpiInjectTest {
    
    @Before
    public void setUp() {
        log.info("========== SPI 依赖注入测试开始 ==========");
    }
    
    @After
    public void tearDown() {
        log.info("========== SPI 依赖注入测试结束 ==========\n");
    }
    
    /**
     * 测试 1：基本依赖注入
     */
    @Test
    public void testBasicInject() {
        log.info("\n========== 测试 1：基本依赖注入 ==========");
        
        DataProcessor processor = ExtensionFactory.getExtension(DataProcessor.class, "default");
        assertNotNull("处理器不应为空", processor);
        
        assertTrue("应该是 DefaultDataProcessor", processor instanceof DefaultDataProcessor);
        DefaultDataProcessor defaultProcessor = (DefaultDataProcessor) processor;
        
        // 验证注入的序列化器
        Serializer serializer = defaultProcessor.getSerializer();
        assertNotNull("序列化器应该被注入", serializer);
        log.info("注入的序列化器: {}", serializer.getClass().getSimpleName());
        
        // 验证注入的 JSON 序列化器
        Serializer jsonSerializer = defaultProcessor.getJsonSerializer();
        assertNotNull("JSON 序列化器应该被注入", jsonSerializer);
        log.info("注入的 JSON 序列化器: {}", jsonSerializer.getClass().getSimpleName());
        
        // 验证可选注入（不存在的扩展）- 关键测试：应该不会抛出异常
        Serializer optionalSerializer = defaultProcessor.getOptionalSerializer();
        assertNull("可选注入不存在的扩展应该为 null", optionalSerializer);
        log.info("可选注入（不存在扩展）: {}", optionalSerializer);
        
        log.info("✓ 基本依赖注入测试通过");
    }
    
    /**
     * 测试 2：required=false 且扩展不存在的场景（关键测试）
     */
    @Test
    public void testOptionalInjectWithNonexistentExtension() {
        log.info("\n========== 测试 2：required=false 且扩展不存在 ==========");
        
        // 获取 OptionalInjectProcessor
        OptionalInjectProcessor processor = (OptionalInjectProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "optional");
        
        assertNotNull("处理器不应为空", processor);
        log.info("处理器创建成功: {}", processor.getName());
        
        // 验证必须注入的扩展
        assertNotNull("必须注入的序列化器不应为空", processor.getRequiredSerializer());
        log.info("必须注入: {}", processor.getRequiredSerializer().getClass().getSimpleName());
        
        // 关键测试：可选注入不存在的扩展，应该为 null，而不是抛出异常
        assertNull("可选注入不存在的扩展应该为 null", 
            processor.getOptionalNonexistentSerializer());
        log.info("可选注入（不存在）: {} (期望为 null)", 
            processor.getOptionalNonexistentSerializer());
        
        // 验证可选注入存在的扩展，应该正常注入
        assertNotNull("可选注入存在的扩展不应为空", 
            processor.getOptionalExistingSerializer());
        assertEquals("应该是 JSON 序列化器", 2, 
            processor.getOptionalExistingSerializer().getSerializerType());
        log.info("可选注入（存在）: {}", 
            processor.getOptionalExistingSerializer().getClass().getSimpleName());
        
        // 验证默认注入
        assertNotNull("默认注入不应为空", processor.getDefaultSerializer());
        log.info("默认注入: {}", processor.getDefaultSerializer().getClass().getSimpleName());
        
        log.info("✓ required=false 且扩展不存在的测试通过");
    }
    
    /**
     * 测试 2：指定名称注入
     */
    @Test
    public void testNamedInject() {
        log.info("\n========== 测试 2：指定名称注入 ==========");
        
        JsonDataProcessor processor = (JsonDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "json");
        assertNotNull("处理器不应为空", processor);
        
        // 验证注入的是 JSON 序列化器
        Serializer jsonSerializer = processor.getJsonSerializer();
        assertNotNull("JSON 序列化器应该被注入", jsonSerializer);
        assertEquals("应该是 JSON 序列化器", 2, jsonSerializer.getSerializerType());
        
        log.info("注入的序列化器类型: {}", jsonSerializer.getSerializerType());
        log.info("✓ 指定名称注入测试通过");
    }
    
    /**
     * 测试 3：多依赖注入
     */
    @Test
    public void testMultipleInject() {
        log.info("\n========== 测试 3：多依赖注入 ==========");
        
        AdvancedDataProcessor processor = (AdvancedDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "advanced");
        assertNotNull("处理器不应为空", processor);
        
        // 验证序列化器注入
        Serializer serializer = processor.getSerializer();
        assertNotNull("序列化器应该被注入", serializer);
        assertEquals("应该是 Kryo 序列化器", 1, serializer.getSerializerType());
        
        // 验证负载均衡器注入
        LoadBalancer loadBalancer = processor.getLoadBalancer();
        assertNotNull("负载均衡器应该被注入", loadBalancer);
        assertEquals("应该是轮询负载均衡器", "roundRobin", loadBalancer.getName());
        
        log.info("注入的序列化器: {}", serializer.getClass().getSimpleName());
        log.info("注入的负载均衡器: {}", loadBalancer.getClass().getSimpleName());
        
        log.info("✓ 多依赖注入测试通过");
    }
    
    /**
     * 测试 4：初始化方法调用
     */
    @Test
    public void testInitializeMethod() {
        log.info("\n========== 测试 4：初始化方法调用 ==========");
        
        DefaultDataProcessor processor = (DefaultDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "default");
        
        // 验证初始化方法被调用
        assertTrue("初始化方法应该被调用", processor.isInitialized());
        
        log.info("初始化状态: {}", processor.isInitialized());
        log.info("✓ 初始化方法调用测试通过");
    }
    
    /**
     * 测试 5：高级处理器初始化
     */
    @Test
    public void testAdvancedInitialize() {
        log.info("\n========== 测试 5：高级处理器初始化 ==========");
        
        AdvancedDataProcessor processor = (AdvancedDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "advanced");
        
        // 验证初始化
        assertTrue("初始化方法应该被调用", processor.isInitialized());
        assertEquals("配置应该被设置", "initialized-config", processor.getConfig());
        
        log.info("初始化状态: {}", processor.isInitialized());
        log.info("配置值: {}", processor.getConfig());
        log.info("✓ 高级处理器初始化测试通过");
    }
    
    /**
     * 测试 6：注入实例的功能验证
     */
    @Test
    public void testInjectedInstanceFunctionality() {
        log.info("\n========== 测试 6：注入实例的功能验证 ==========");
        
        DataProcessor processor = ExtensionFactory.getExtension(DataProcessor.class, "default");
        
        String data = "Hello SPI Inject!";
        String result = processor.process(data);
        
        assertNotNull("处理结果不应为空", result);
        assertTrue("结果应该包含原始数据", result.contains(data));
        assertTrue("结果应该包含处理器名称", result.contains("DefaultDataProcessor"));
        
        log.info("处理结果: {}", result);
        log.info("✓ 注入实例功能验证通过");
    }
    
    /**
     * 测试 7：单例验证
     */
    @Test
    public void testInjectSingleton() {
        log.info("\n========== 测试 7：单例验证 ==========");
        
        // 获取默认序列化器
        Serializer defaultSerializer = ExtensionFactory.getDefaultExtension(Serializer.class);
        
        // 获取处理器
        DefaultDataProcessor processor = (DefaultDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "default");
        
        // 验证注入的是同一个实例
        assertSame("注入的应该是同一个序列化器实例", 
            defaultSerializer, processor.getSerializer());
        
        log.info("默认序列化器: {}", defaultSerializer.hashCode());
        log.info("注入的序列化器: {}", processor.getSerializer().hashCode());
        log.info("✓ 单例验证通过");
    }
    
    /**
     * 测试 8：不同处理器的不同依赖
     */
    @Test
    public void testDifferentProcessorsDifferentDependencies() {
        log.info("\n========== 测试 8：不同处理器的不同依赖 ==========");
        
        DefaultDataProcessor defaultProcessor = (DefaultDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "default");
        JsonDataProcessor jsonProcessor = (JsonDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "json");
        AdvancedDataProcessor advancedProcessor = (AdvancedDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "advanced");
        
        // 验证默认处理器使用默认序列化器
        assertEquals("默认处理器应使用默认序列化器", 
            1, defaultProcessor.getSerializer().getSerializerType());
        
        // 验证 JSON 处理器使用 JSON 序列化器
        assertEquals("JSON 处理器应使用 JSON 序列化器", 
            2, jsonProcessor.getJsonSerializer().getSerializerType());
        
        // 验证高级处理器使用 Kryo 序列化器
        assertEquals("高级处理器应使用 Kryo 序列化器", 
            1, advancedProcessor.getSerializer().getSerializerType());
        
        log.info("默认处理器序列化器类型: {}", defaultProcessor.getSerializer().getSerializerType());
        log.info("JSON 处理器序列化器类型: {}", jsonProcessor.getJsonSerializer().getSerializerType());
        log.info("高级处理器序列化器类型: {}", advancedProcessor.getSerializer().getSerializerType());
        
        log.info("✓ 不同处理器使用不同依赖测试通过");
    }
    
    /**
     * 测试 9：处理数据验证
     */
    @Test
    public void testProcessData() {
        log.info("\n========== 测试 9：处理数据验证 ==========");
        
        String testData = "Test Data for SPI Inject";
        
        // 测试默认处理器
        DataProcessor defaultProcessor = ExtensionFactory.getExtension(DataProcessor.class, "default");
        String result1 = defaultProcessor.process(testData);
        log.info("默认处理器结果: {}", result1);
        
        // 测试 JSON 处理器
        DataProcessor jsonProcessor = ExtensionFactory.getExtension(DataProcessor.class, "json");
        String result2 = jsonProcessor.process(testData);
        log.info("JSON 处理器结果: {}", result2);
        
        // 测试高级处理器
        DataProcessor advancedProcessor = ExtensionFactory.getExtension(DataProcessor.class, "advanced");
        String result3 = advancedProcessor.process(testData);
        log.info("高级处理器结果: {}", result3);
        
        // 验证结果
        assertTrue("默认处理器结果应包含原始数据", result1.contains(testData));
        assertTrue("JSON 处理器结果应包含原始数据", result2.contains(testData));
        assertTrue("高级处理器结果应包含原始数据", result3.contains(testData));
        
        log.info("✓ 处理数据验证通过");
    }
    
    /**
     * 测试 10：扩展点注解验证
     */
    @Test
    public void testExtensionPointAnnotation() {
        log.info("\n========== 测试 10：扩展点注解验证 ==========");
        
        // 验证 DataProcessor 是扩展点
        assertTrue("DataProcessor 应该是扩展点", 
            DataProcessor.class.isAnnotationPresent(SPI.class));
        
        SPI spi = DataProcessor.class.getAnnotation(SPI.class);
        assertEquals("默认实现应该是 default", "default", spi.value());
        
        // 验证支持的扩展
        assertTrue("应该支持 default", 
            ExtensionFactory.hasExtension(DataProcessor.class, "default"));
        assertTrue("应该支持 json", 
            ExtensionFactory.hasExtension(DataProcessor.class, "json"));
        assertTrue("应该支持 advanced", 
            ExtensionFactory.hasExtension(DataProcessor.class, "advanced"));
        
        log.info("✓ 扩展点注解验证通过");
    }
}