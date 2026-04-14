package com.rpc.core.transport.server;

import com.rpc.core.protocol.message.RpcMessage;

/**
 * 服务端请求处理器抽象。
 *
 * 所处阶段：Netty/Socket 服务端解码出 RpcMessage 后，进入业务分发前后。
 * 主要职责：把传输层消息交给统一的请求处理逻辑，屏蔽底层传输差异。
 */
public interface RpcRequestProcessor {
    /**
     * 处理一条 RPC 消息并返回响应消息。
     *
     * 边界处理：实现类需要识别心跳、普通请求和异常请求，避免把非业务消息送入反射调用。
     */
    RpcMessage process(RpcMessage message);
}

