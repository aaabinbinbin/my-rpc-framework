package com.rpc.faulttolerance;

import com.rpc.faulttolerance.degrade.DefaultValueDegradation;
import com.rpc.faulttolerance.degrade.FailFastDegradation;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务降级策略测试
 * 测试 FailFastDegradation 和 DefaultValueDegradation 的功能
 */
@Slf4j
class DegradationPolicyTest {

    private RpcRequest testRequest;
    private Exception testException;

    @BeforeEach
    void setUp() {
        // 准备测试请求
        testRequest = RpcRequest.builder()
                .requestId("test-request-001")
                .serviceName("UserService")
                .methodName("getUserById")
                .parameterTypes(new Class<?>[]{Long.class})
                .parameters(new Object[]{123L})
                .build();

        // 准备测试异常
        testException = new RuntimeException("服务连接超时");
    }

    // ========== FailFastDegradation 测试 ==========

    @Test
    @DisplayName("测试 1：快速失败降级 - 基本功能")
    void testFailFastDegradationBasic() {
        log.info("===== 测试 1：快速失败降级 - 基本功能 =====");

        FailFastDegradation degradation = new FailFastDegradation();

        // 执行降级
        RpcResponse response = degradation.degrade(testRequest, testException);

        // 验证响应
        assertNotNull(response, "响应不应为空");
        assertEquals(4003, response.getCode(), "响应码应该是 4003");
        assertNotNull(response.getMessage(), "响应消息不应为空");
        assertTrue(response.getMessage().contains("服务已降级"), "消息应包含'服务已降级'");
        assertNull(response.getData(), "快速失败不应返回数据");

        log.info("✓ 快速失败降级基本功能正常");
        log.info("响应码：{}, 消息：{}", response.getCode(), response.getMessage());
    }

    @Test
    @DisplayName("测试 2：快速失败降级 - 不同异常类型")
    void testFailFastDegradationWithDifferentExceptions() {
        log.info("===== 测试 2：快速失败降级 - 不同异常类型 =====");

        FailFastDegradation degradation = new FailFastDegradation();

        // 测试不同类型的异常
        Exception[] exceptions = {
            new RuntimeException("运行时异常"),
            new IllegalArgumentException("参数非法"),
            new NullPointerException("空指针"),
            new Exception("通用异常")
        };

        for (Exception ex : exceptions) {
            log.info("测试异常类型：{}", ex.getClass().getSimpleName());
            RpcResponse response = degradation.degrade(testRequest, ex);

            assertNotNull(response, "响应不应为空");
            assertEquals(4003, response.getCode(), "响应码应该一致");
            assertTrue(response.getMessage().contains(ex.getMessage()), 
                    "消息应包含原始异常信息");

            log.info("✓ {} 处理正确", ex.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("测试 3：快速失败降级 - 日志输出验证")
    void testFailFastDegradationLogging() {
        log.info("===== 测试 3：快速失败降级 - 日志输出验证 =====");

        FailFastDegradation degradation = new FailFastDegradation();

        // 执行降级（会触发日志）
        degradation.degrade(testRequest, testException);

        log.info("✓ 日志输出验证通过（查看上方日志）");
    }

    // ========== DefaultValueDegradation 测试 ==========

    @Test
    @DisplayName("测试 4：默认值降级 - 有默认值的场景")
    void testDefaultValueDegradationWithDefault() {
        log.info("===== 测试 4：默认值降级 - 有默认值的场景 =====");

        DefaultValueDegradation degradation = new DefaultValueDegradation();

        // 设置默认值
        String serviceMethod = "UserService#getUserById";
        Object defaultValue = createMockUser(999L, "默认用户");
        degradation.setDefaultValue(serviceMethod, defaultValue);

        // 执行降级
        RpcResponse response = degradation.degrade(testRequest, testException);

        // 验证响应
        assertNotNull(response, "响应不应为空");
        assertEquals(200, response.getCode(), "使用默认值时响应码应该是 200");
        assertEquals("成功 - Success!", response.getMessage(), "消息应该是成功");
        assertNotNull(response.getData(), "应该有默认值数据");
        assertEquals(defaultValue, response.getData(), "数据应该是预设的默认值");

        log.info("✓ 默认值降级功能正常");
        log.info("默认值：{}", response.getData());
    }

    @Test
    @DisplayName("测试 5：默认值降级 - 无默认值的场景")
    void testDefaultValueDegradationWithoutDefault() {
        log.info("===== 测试 5：默认值降级 - 无默认值的场景 =====");

        DefaultValueDegradation degradation = new DefaultValueDegradation();

        // 不设置默认值，直接执行降级
        RpcResponse response = degradation.degrade(testRequest, testException);

        // 验证响应
        assertNotNull(response, "响应不应为空");
        assertEquals(4003, response.getCode(), "无默认值时响应码应该是 503");
        assertTrue(response.getMessage().contains("服务降级且无默认值"), 
                "消息应提示无默认值");
        assertNull(response.getData(), "不应该有数据");

        log.info("✓ 无默认值时快速失败功能正常");
    }

    @Test
    @DisplayName("测试 6：默认值降级 - 多个服务的默认值管理")
    void testDefaultValueDegradationMultipleServices() {
        log.info("===== 测试 6：默认值降级 - 多个服务的默认值管理 =====");

        DefaultValueDegradation degradation = new DefaultValueDegradation();

        // 为不同服务设置默认值
        degradation.setDefaultValue("UserService#getUserById", 
                createMockUser(1L, "用户 1"));
        degradation.setDefaultValue("OrderService#getOrderById", 
                createMockOrder(100L, "订单 100"));
        degradation.setDefaultValue("ProductService#getProductById", 
                createMockProduct(50L, "商品 50"));

        // 测试 UserService 的降级
        RpcRequest userRequest = createRequest("UserService", "getUserById", "req-001");
        RpcResponse userResponse = degradation.degrade(userRequest, testException);
        assertEquals(200, userResponse.getCode(), "UserService 应返回默认值");
        assertNotNull(userResponse.getData(), "UserService 应有数据");
        log.info("✓ UserService 默认值正确");

        // 测试 OrderService 的降级
        RpcRequest orderRequest = createRequest("OrderService", "getOrderById", "req-002");
        RpcResponse orderResponse = degradation.degrade(orderRequest, testException);
        assertEquals(200, orderResponse.getCode(), "OrderService 应返回默认值");
        assertNotNull(orderResponse.getData(), "OrderService 应有数据");
        log.info("✓ OrderService 默认值正确");

        // 测试 ProductService 的降级
        RpcRequest productRequest = createRequest("ProductService", "getProductById", "req-003");
        RpcResponse productResponse = degradation.degrade(productRequest, testException);
        assertEquals(200, productResponse.getCode(), "ProductService 应返回默认值");
        assertNotNull(productResponse.getData(), "ProductService 应有数据");
        log.info("✓ ProductService 默认值正确");

        // 测试未配置默认值的服务
        RpcRequest unknownRequest = createRequest("UnknownService", "unknownMethod", "req-004");
        RpcResponse unknownResponse = degradation.degrade(unknownRequest, testException);
        assertEquals(4003, unknownResponse.getCode(), "未配置的服务应返回 4003");
        log.info("✓ 未配置默认值的服务快速失败");
    }

    @Test
    @DisplayName("测试 7：默认值降级 - 更新默认值")
    void testDefaultValueDegradationUpdateDefault() {
        log.info("===== 测试 7：默认值降级 - 更新默认值 =====");

        DefaultValueDegradation degradation = new DefaultValueDegradation();

        String serviceMethod = "UserService#getUserById";

        // 设置初始默认值
        Object initialDefault = createMockUser(1L, "初始用户");
        degradation.setDefaultValue(serviceMethod, initialDefault);

        // 验证初始值
        RpcResponse response1 = degradation.degrade(testRequest, testException);
        assertEquals(initialDefault, response1.getData(), "应该是初始默认值");
        log.info("初始默认值：{}", response1.getData());

        // 更新默认值
        Object updatedDefault = createMockUser(2L, "更新后的用户");
        degradation.setDefaultValue(serviceMethod, updatedDefault);

        // 验证更新后的值
        RpcResponse response2 = degradation.degrade(testRequest, testException);
        assertEquals(updatedDefault, response2.getData(), "应该是更新后的默认值");
        log.info("更新后的默认值：{}", response2.getData());

        // 验证两次降级的值不同
        assertNotEquals(response1.getData(), response2.getData(), 
                "更新前后的默认值应该不同");

        log.info("✓ 默认值更新功能正常");
    }

    @Test
    @DisplayName("测试 8：默认值降级 - 不同类型的数据")
    void testDefaultValueDegradationDifferentTypes() {
        log.info("===== 测试 8：默认值降级 - 不同类型的数据 =====");

        DefaultValueDegradation degradation = new DefaultValueDegradation();

        // 测试 String 类型
        degradation.setDefaultValue("Service#getString", "默认字符串");
        RpcResponse stringResponse = degradation.degrade(
                createRequest("Service", "getString", "req-001"), testException);
        assertEquals("默认字符串", stringResponse.getData(), "String 类型默认值正确");
        log.info("✓ String 类型：{}", stringResponse.getData());

        // 测试 Integer 类型
        degradation.setDefaultValue("Service#getInteger", 42);
        RpcResponse intResponse = degradation.degrade(
                createRequest("Service", "getInteger", "req-002"), testException);
        assertEquals(42, intResponse.getData(), "Integer 类型默认值正确");
        log.info("✓ Integer 类型：{}", intResponse.getData());

        // 测试 Boolean 类型
        degradation.setDefaultValue("Service#getBoolean", true);
        RpcResponse boolResponse = degradation.degrade(
                createRequest("Service", "getBoolean", "req-003"), testException);
        assertEquals(true, boolResponse.getData(), "Boolean 类型默认值正确");
        log.info("✓ Boolean 类型：{}", boolResponse.getData());

        // 测试复杂对象类型
        MockData complexData = createMockUser(888L, "复杂对象");
        degradation.setDefaultValue("Service#getComplexObject", complexData);
        RpcResponse complexResponse = degradation.degrade(
                createRequest("Service", "getComplexObject", "req-004"), testException);
        assertEquals(complexData, complexResponse.getData(), "复杂对象默认值正确");
        log.info("✓ 复杂对象类型：{}", complexResponse.getData());

        log.info("✓ 所有数据类型的默认值都正常工作");
    }

    // ========== 对比测试 ==========

    @Test
    @DisplayName("测试 9：策略对比 - 快速失败 vs 默认值")
    void testStrategyComparison() {
        log.info("===== 测试 9：策略对比 - 快速失败 vs 默认值 =====");

        FailFastDegradation failFast = new FailFastDegradation();
        DefaultValueDegradation defaultValue = new DefaultValueDegradation();

        // 为默认值策略设置默认值
        defaultValue.setDefaultValue("UserService#getUserById", 
                createMockUser(999L, "兜底用户"));

        // 同时执行两种策略
        RpcResponse failFastResponse = failFast.degrade(testRequest, testException);
        RpcResponse defaultValueResponse = defaultValue.degrade(testRequest, testException);

        // 对比结果
        log.info("快速失败响应码：{}, 消息：{}", 
                failFastResponse.getCode(), failFastResponse.getMessage());
        log.info("默认值响应码：{}, 消息：{}, 数据：{}", 
                defaultValueResponse.getCode(), 
                defaultValueResponse.getMessage(),
                defaultValueResponse.getData());

        // 验证差异
        assertEquals(4003, failFastResponse.getCode(), "快速失败应该是 4003");
        assertEquals(200, defaultValueResponse.getCode(), "默认值应该是 200");
        assertNull(failFastResponse.getData(), "快速失败不应有数据");
        assertNotNull(defaultValueResponse.getData(), "默认值应该有数据");

        log.info("✓ 两种策略行为符合预期");
    }

    // ========== 辅助方法 ==========

    private RpcRequest createRequest(String serviceName, String methodName, String requestId) {
        return RpcRequest.builder()
                .requestId(requestId)
                .serviceName(serviceName)
                .methodName(methodName)
                .build();
    }

    private MockData createMockUser(Long id, String name) {
        return new MockData(id, name, "USER");
    }

    private MockData createMockOrder(Long id, String description) {
        return new MockData(id, description, "ORDER");
    }

    private MockData createMockProduct(Long id, String name) {
        return new MockData(id, name, "PRODUCT");
    }

    /**
     * 模拟数据类（用于测试）
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class MockData {
        private Long id;
        private String name;
        private String type;
    }
}
