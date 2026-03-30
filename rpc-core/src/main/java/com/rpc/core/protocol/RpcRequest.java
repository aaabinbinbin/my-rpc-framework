package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

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

    @Builder.Default
    /**
     * attachments（附加字段）用于承载与业务参数解耦的调用元信息，例如：
     * traceId（链路标识）、方法级超时覆盖、序列化器覆盖、过滤器透传字段等。
     */
    private Map<String, String> attachments = new HashMap<>();
}
