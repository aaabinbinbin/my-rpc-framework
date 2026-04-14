package com.rpc.core.resilience.degrade;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认值降级策略。
 *
 * 所处阶段：consumer 或 provider 过滤器判断需要降级时。
 * 主要职责：按 serviceName#methodName 查找预配置默认值，构造成功响应返回给调用方。
 *
 * 边界处理：未配置默认值时返回 SERVICE_DEGRADED 错误响应，而不是返回 null 成功值。
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultValueDegradation implements DegradationPolicy {
    /** 方法级默认值映射，key 格式为 serviceName#methodName。 */
    private final Map<String, Object> defaultValues;

    /**
     * 创建空默认值降级策略。
     */
    public DefaultValueDegradation() {
        this(new ConcurrentHashMap<>());
    }

    /**
     * 设置某个服务方法的降级默认值。
     */
    public void setDefaultValue(String serviceMethod, Object value) {
        defaultValues.put(serviceMethod, value);
        log.info("Default degradation value configured: {} = {}", serviceMethod, value);
    }

    /**
     * 执行默认值降级。
     */
    @Override
    public RpcResponse degrade(RpcRequest request, Throwable cause) {
        String key = request.getServiceName() + "#" + request.getMethodName();
        Object defaultValue = defaultValues.get(key);
        if (defaultValue != null) {
            log.info("Using default degradation value: {} = {}", key, defaultValue);
            return RpcResponse.success(defaultValue, request.getRequestId());
        }
        log.warn("No default degradation value configured for {}", key);
        return RpcResponse.fail(
                ErrorCode.SERVICE_DEGRADED.getCode(),
                "Service degraded without default value",
                request.getRequestId()
        );
    }
}
