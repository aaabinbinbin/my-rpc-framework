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
 * 客户端消息处理器。
 */
@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {
    private final RequestManager requestManager;

    public RpcClientHandler(RequestManager requestManager) {
        this.requestManager = requestManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        RpcMessageType messageType = RpcMessageType.fromCode(message.getHeader().getMessageType());
        // 客户端目前只关心两类入站消息：业务响应和心跳响应。
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

    private void handleBusinessResponse(RpcMessage message) {
        RpcResponse response = (RpcResponse) message.getBody();
        // 响应回到 RequestManager 后，会唤醒 sendRequest() 中等待中的 future。
        requestManager.completeResponse(response);
    }

    private void handleHeartbeatResponse(RpcMessage message) {
        RpcHeartbeat heartbeat = (RpcHeartbeat) message.getBody();
        long latency = System.currentTimeMillis() - heartbeat.getTimestamp();
        log.debug("Received heartbeat response requestId={}, latency={}ms",
                heartbeat.getRequestId(), latency);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Client handler caught exception", cause);
        ctx.close();
    }
}
