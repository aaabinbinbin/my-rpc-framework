package com.rpc.core.invoke.filter;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.invocation.InvocationOptions;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Builder
public class FilterContext {
    private final RpcContext rpcContext;
    private final RpcRequest request;
    private RpcResponse response;
    private final InvocationOptions invocationOptions;
    private final Class<?> serviceClass;
    private final Object serviceBean;

    @Builder.Default
    private final Map<String, Object> attributes = new HashMap<>();

    public void setResponse(RpcResponse response) {
        this.response = response;
    }

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return value == null ? null : (T) value;
    }
}

