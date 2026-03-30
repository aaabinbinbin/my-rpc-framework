package com.rpc.core.transport.netty.client.handler.heart;

import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcHeartbeat;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.RpcMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 当客户端通道持续处于写空闲时发送心跳请求。
 */
@Slf4j
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.WRITER_IDLE) {
                // 只有在写空闲时发心跳，说明这段时间没有业务请求出站。
                log.debug("Writer idle detected, sending heartbeat");
                sendHeartbeat(ctx);
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    private void sendHeartbeat(ChannelHandlerContext ctx) {
        try {
            long requestId = System.nanoTime();

            // 心跳也复用统一 RPC 协议，只是 messageType 不同。
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) 0)
                    .messageType(RpcMessageType.HEARTBEAT_REQUEST.getCode())
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();

            RpcHeartbeat heartbeat = RpcHeartbeat.createRequest(requestId);

            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(heartbeat);

            ctx.writeAndFlush(message).addListener(future -> {
                if (!future.isSuccess()) {
                    log.warn("Failed to send heartbeat", future.cause());
                } else {
                    log.debug("Heartbeat sent successfully, requestId={}", requestId);
                }
            });
        } catch (Exception e) {
            log.error("Failed to build or send heartbeat", e);
        }
    }
}
