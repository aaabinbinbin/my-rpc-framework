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
public class RpcResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 请求 ID（与请求对应） */
    private String requestId;

    /** 响应状态码 */
    private Integer code;

    /** 响应消息 */
    private String message;

    /** 响应数据 */
    private Object data;

    /** 创建成功响应 */
    public static RpcResponse success(Object data, String requestId) {
        return RpcResponse.builder()
                .requestId(requestId)
                .code(200)
                .message("成功 - Success!")
                .data(data)
                .build();
    }

    /** 创建失败响应 */
    public static RpcResponse fail(Integer code, String message, String requestId) {
        return RpcResponse.builder()
                .requestId(requestId)
                .code(code)
                .message(message)
                .build();
    }
}
