package com.rpc.core.invoke.context;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RpcContext {
/**
 * 调用上下文与当前线程绑定。
 * 消费端会在发送请求前填充它，服务端则会在收到请求后从附件中重建它。
 */
    private static final ThreadLocal<RpcContext> CONTEXT = ThreadLocal.withInitial(RpcContext::new);

    private String requestId;
    private String traceId;
    private final Map<String, String> attachments = new HashMap<>();

    public static RpcContext getContext() {
        return CONTEXT.get();
    }

    public static RpcContext create() {
    // create() 用于显式创建一个全新的调用上下文，
    // 避免复用线程里之前遗留的 ThreadLocal 状态。
        RpcContext context = new RpcContext();
        CONTEXT.set(context);
        return context;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public String getRequestId() {
        return requestId;
    }

    public RpcContext setRequestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public String getTraceId() {
        return traceId;
    }

    public RpcContext setTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public Map<String, String> getAttachments() {
        return attachments;
    }

    public RpcContext putAttachment(String key, String value) {
        if (key != null && value != null) {
            attachments.put(key, value);
        }
        return this;
    }

    public String ensureTraceId() {
        if (traceId == null || traceId.isBlank()) {
    // traceId 采用懒生成，
    // 这样本地路径在真正需要链路追踪信息之前不会额外付出开销。
            traceId = UUID.randomUUID().toString();
        }
        return traceId;
    }
}

