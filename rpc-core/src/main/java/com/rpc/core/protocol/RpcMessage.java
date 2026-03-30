package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 同时承载消息头和消息体的协议封装对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcMessage {
    private RpcHeader header;
    private Object body;
}
