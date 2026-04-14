package com.rpc.core.resilience.degrade;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.resilience.DegradationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 降级策略工厂测试。
 *
 * <p>测试目标：验证配置字符串到降级策略对象的转换逻辑，尤其是未知策略兜底、
 * 默认值类型解析、未配置默认值时的失败响应，保证熔断或限流触发后的降级数据流有明确语义。</p>
 */
@DisplayName("降级策略工厂测试")
class DegradationPolicyFactoryTest {

    @Test
    @DisplayName("空策略名和未知策略名应兜底为快速失败降级")
    void shouldFallbackToFailFastWhenPolicyNameMissingOrUnknown() {
        assertInstanceOf(FailFastDegradation.class, DegradationPolicyFactory.create(null, null));
        assertInstanceOf(FailFastDegradation.class, DegradationPolicyFactory.create("unknown", Map.of()));
    }

    @Test
    @DisplayName("默认值降级策略应解析常见标量类型")
    void defaultValuePolicyShouldParseScalarValues() {
        DegradationPolicy policy = DegradationPolicyFactory.create(
                "defaultValue",
                Map.of(
                        "svc#boolMethod", "true",
                        "svc#intMethod", "42",
                        "svc#longMethod", "1234567890123",
                        "svc#doubleMethod", "3.14",
                        "svc#stringMethod", " fallback "
                )
        );

        assertInstanceOf(DefaultValueDegradation.class, policy);
        assertEquals(Boolean.TRUE, degrade(policy, "boolMethod").getData());
        assertEquals(42, degrade(policy, "intMethod").getData());
        assertEquals(1234567890123L, degrade(policy, "longMethod").getData());
        assertEquals(3.14D, degrade(policy, "doubleMethod").getData());
        assertEquals("fallback", degrade(policy, "stringMethod").getData());
    }

    @Test
    @DisplayName("默认值降级策略缺少方法默认值时应返回明确失败响应")
    void defaultValuePolicyShouldReturnFailureWhenDefaultValueMissing() {
        DegradationPolicy policy = DegradationPolicyFactory.create("defaultValue", Map.of());

        RpcResponse response = degrade(policy, "missingMethod");

        assertEquals(ErrorCode.SERVICE_DEGRADED.getCode(), response.getCode());
        assertEquals("request-1", response.getRequestId());
        assertNull(response.getData());
    }

    private RpcResponse degrade(DegradationPolicy policy, String methodName) {
        RpcRequest request = RpcRequest.builder()
                .requestId("request-1")
                .serviceName("svc")
                .methodName(methodName)
                .build();
        return policy.degrade(request, new RuntimeException("test degradation"));
    }
}
