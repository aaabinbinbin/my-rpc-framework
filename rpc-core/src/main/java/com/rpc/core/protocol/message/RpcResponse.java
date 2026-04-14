package com.rpc.core.protocol.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RPC 响应体。
 *
 * 所处阶段：provider 执行业务方法或框架治理逻辑后构造该对象，
 * consumer 收到后按 requestId 匹配 pending 请求，并根据 code 判断成功或失败。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String requestId;

    /**
     * code（状态码）/message（消息）/data（数据）采用通用响应模型，
     * 避免 transport（传输层）和调用层直接依赖具体异常实现或业务返回类型。
     */
    private Integer code;
    private String message;
    private Object data;

    /** 构造成功响应；当前项目约定 code=200 才表示真实成功。 */
    public static RpcResponse success(Object data, String requestId) {
        return RpcResponse.builder()
                .requestId(requestId)
                .code(200)
                .message("Success")
                .data(data)
                .build();
    }

    /** 构造失败响应；限流、繁忙、熔断、服务不存在等都通过非 200 code 表达。 */
    public static RpcResponse fail(Integer code, String message, String requestId) {
        return RpcResponse.builder()
                .requestId(requestId)
                .code(code)
                .message(message)
                .build();
    }
}
