package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

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

    public static RpcResponse success(Object data, String requestId) {
        return RpcResponse.builder()
                .requestId(requestId)
                .code(200)
                .message("Success")
                .data(data)
                .build();
    }

    public static RpcResponse fail(Integer code, String message, String requestId) {
        return RpcResponse.builder()
                .requestId(requestId)
                .code(code)
                .message(message)
                .build();
    }
}
