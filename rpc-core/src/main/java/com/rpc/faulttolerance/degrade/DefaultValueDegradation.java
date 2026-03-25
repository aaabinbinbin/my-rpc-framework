package com.rpc.faulttolerance.degrade;

import com.rpc.common.constant.ErrorCode;
import com.rpc.faulttolerance.DegradationPolicy;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认值降级策略
 * 返回预设的默认值
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultValueDegradation implements DegradationPolicy {
    /** 服务的默认返回值映射 */
    private final Map<String, Object> defaultValues = new ConcurrentHashMap<>();

    /**
     * 设置服务的默认返回值
     */
    public void setDefaultValue(String serviceMethod, Object value) {
        defaultValues.put(serviceMethod, value);
        log.info("设置默认值：{} = {}", serviceMethod, value);
    }

    @Override
    public RpcResponse degrade(RpcRequest request, Throwable cause) {
        String key = request.getServiceName() + "#" + request.getMethodName();
        Object defaultValue = defaultValues.get(key);

        if (defaultValue != null) {
            log.info("使用默认值降级：{} = {}", key, defaultValue);
            return RpcResponse.success(defaultValue, request.getRequestId());
        }

        log.warn("无默认值，使用快速失败：{}", key);
        return RpcResponse.fail(ErrorCode.SERVICE_DEGRADED.getCode(), "服务降级且无默认值", request.getRequestId());
    }
}
