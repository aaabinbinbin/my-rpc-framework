package com.rpc.core.transport.netty.client.handler;

import com.rpc.core.protocol.RpcHeartbeat;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.RpcMessageType;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.transport.netty.client.manager.RequestManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端入站消息处理器。
 *
 * 这个类只处理客户端真正关心的两类入站消息：
 * 1. 业务响应。
 * 2. 心跳响应。
 *
 * 它不负责协议解码，前面的 decoder 已经把字节流还原成 RpcMessage 了。
 */
@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {
    /** 请求管理器，用于把响应回填给等待中的 future。 */
    private final RequestManager requestManager;

    public RpcClientHandler(RequestManager requestManager) {
        this.requestManager = requestManager;
    }

    /**
     * 处理客户端收到的 RpcMessage。
     *
     * 根据 messageType 分发到不同处理分支，
     * 目前主要支持业务响应和心跳响应。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        RpcMessageType messageType = RpcMessageType.fromCode(message.getHeader().getMessageType());
        switch (messageType) {
            case HEARTBEAT_RESPONSE:
                handleHeartbeatResponse(message);
                break;
            case RESPONSE:
                handleBusinessResponse(message);
                break;
            default:
                log.warn("Unsupported client message type: {}", messageType);
        }
    }

    /**
     * 处理业务响应。
     *
     * 响应回到 RequestManager 之后，
     * 原来 sendRequest() 中等待 future 的调用方就可以继续向下执行。
     */
    private void handleBusinessResponse(RpcMessage message) {
        RpcResponse response = (RpcResponse) message.getBody();
        requestManager.completeResponse(response);
    }

    /** 处理心跳响应，并记录一次简单的往返延迟。 */
    private void handleHeartbeatResponse(RpcMessage message) {
        RpcHeartbeat heartbeat = (RpcHeartbeat) message.getBody();
        long latency = System.currentTimeMillis() - heartbeat.getTimestamp();
        log.debug("Received heartbeat response requestId={}, latency={}ms",
                heartbeat.getRequestId(), latency);
    }

    /** 客户端 handler 层发生异常时关闭当前连接。 */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Client handler caught exception", cause);
        ctx.close();
    }
}
