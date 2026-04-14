package com.rpc.core.invoke.filter.context;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.invocation.InvocationOptions;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 过滤器上下文。
 *
 * 这是 filter 链中各个节点共享的数据载体，
 * 用来把本次调用的核心信息和临时属性在链路中传递下去。
 */
@Getter
@Builder
public class FilterContext {
    /** 线程级 RPC 上下文，里面通常保存 requestId、traceId 和 attachments。 */
    private final RpcContext rpcContext;
    /** 当前请求对象。 */
    private final RpcRequest request;
    /** 当前响应对象，通常在 provider 或 consumer 返回阶段写入。 */
    private RpcResponse response;
    /** 当前调用已经解析出的 InvocationOptions。 */
    private final InvocationOptions invocationOptions;
    private final CircuitBreakerManager circuitBreakerManager;
    /** 当前服务接口 / 服务类类型。 */
    private final Class<?> serviceClass;
    /** provider 侧本地服务对象。 */
    private final Object serviceBean;

    /** 过滤器链自定义属性区，用于在不同 filter 之间共享临时数据。 */
    @Builder.Default
    private final Map<String, Object> attributes = new HashMap<>();

    /** 写入响应对象。 */
    public void setResponse(RpcResponse response) {
        this.response = response;
    }

    /** 写入一个自定义属性。 */
    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /** 按类型读取一个自定义属性。 */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return value == null ? null : (T) value;
    }
}
