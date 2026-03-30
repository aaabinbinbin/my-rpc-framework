package com.rpc.core.resilience.degrade;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class DefaultValueDegradation implements DegradationPolicy {
    private final Map<String, Object> defaultValues;

    public DefaultValueDegradation() {
        this(new ConcurrentHashMap<>());
    }

    public void setDefaultValue(String serviceMethod, Object value) {
        defaultValues.put(serviceMethod, value);
        log.info("Default degradation value configured: {} = {}", serviceMethod, value);
    }

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
