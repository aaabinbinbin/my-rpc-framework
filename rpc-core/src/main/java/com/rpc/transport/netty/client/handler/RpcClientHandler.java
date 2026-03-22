package com.rpc.transport.netty.client.handler;

import com.rpc.protocol.RpcHeartbeat;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.transport.netty.client.manager.RequestManager;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import com.rpc.protocol.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 客户端处理器
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

        switch (messageType) {
            case HEARTBEAT_RESPONSE:
                // 处理心跳响应
                handleHeartbeatResponse(message);
                break;

            case RESPONSE:
                // 处理业务响应
                handleBusinessResponse(message);
                break;

            default:
                log.warn("未知消息类型：{}", messageType);
        }
    }

    /**
     * 处理响应
     */
    private void handleBusinessResponse(RpcMessage message) {
        RpcResponse response = (RpcResponse) message.getBody();
        // 通知请求管理器完成 Future
        requestManager.completeResponse(response);
    }

    /**
     * 处理心跳响应
     */
    private void handleHeartbeatResponse(RpcMessage message) {
        RpcHeartbeat heartbeat = (RpcHeartbeat) message.getBody();
        long requestId = heartbeat.getRequestId();
        long timestamp = heartbeat.getTimestamp();

        // 计算往返延迟
        long current = System.currentTimeMillis();
        long latency = current - timestamp;

        log.debug("收到心跳响应：requestId={}, 延迟={}ms", requestId, latency);

        // 可以在这里记录心跳统计信息
        // heartbeatStats.recordLatency(latency);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端异常", cause);
        ctx.close();
    }
}
