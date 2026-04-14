package com.rpc.core.invoke.invocation;

/**
 * 方法级调用选项写入 RpcRequest attachments 时使用的 key。
 *
 * 所处阶段：consumer 侧解析方法级配置后，将部分选项传递给后续过滤器、传输层或 provider 侧。
 * 主要职责：集中维护 attachment key，避免调用链各处硬编码字符串导致拼写不一致。
 */
public final class InvocationAttachmentKeys {
    /** 方法级读超时覆盖值。 */
    public static final String READ_TIMEOUT = "invocation.readTimeout";
    /** 方法级序列化器覆盖值。 */
    public static final String SERIALIZER = "invocation.serializer";
    /** 方法级负载均衡器覆盖值。 */
    public static final String LOAD_BALANCER = "invocation.loadBalancer";

    /** 常量类不允许实例化。 */
    private InvocationAttachmentKeys() {
    }
}

