package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 请求 ID（用于匹配响应） */
    private String requestId;

    /** 服务类名 */
    private String serviceName;

    /** 方法名 */
    private String methodName;

    /** 参数类型数组 */
    private Class<?>[] parameterTypes;

    /** 参数值数组 */
    private Object[] parameters;

    /** 返回值类型 */
    private Class<?> returnType;
}
