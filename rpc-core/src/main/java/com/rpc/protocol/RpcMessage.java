package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RPC 完整消息（消息头 + 消息体）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcMessage {
    /** 消息头 */
    private RpcHeader header;

    /** 消息体（可能是 RpcRequest 或 RpcResponse） */
    private Object body;
}
