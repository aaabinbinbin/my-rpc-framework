package com.rpc.core.protocol.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * RPC 请求体。
 *
 * 所处阶段：consumer 代理层把一次本地接口调用转换成该对象，
 * 后续会经过过滤器、调用编排、序列化和 Netty 发送到 provider。
 *
 * 注意：requestId 表示一次真实网络请求；同一次业务调用如果发生重试，会生成新的 requestId。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String requestId;

    /**
     * serviceName（服务名）/methodName（方法名）/parameterTypes（参数类型）/
     * parameters（参数值）共同描述一次远程方法调用，
     * 本质上是把本地 Java 调用转换成可跨进程传输的调用模型。
     */
    private String serviceName;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] parameters;
    private Class<?> returnType;

    /**
     * attachments（附加字段）用于承载与业务参数解耦的调用元信息，例如：
     * traceId（链路标识）、方法级超时覆盖、序列化器覆盖、过滤器透传字段等。
     */
    @Builder.Default
    private Map<String, String> attachments = new HashMap<>();
}
