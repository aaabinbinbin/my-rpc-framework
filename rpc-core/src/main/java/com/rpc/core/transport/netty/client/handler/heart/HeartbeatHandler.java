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
 * 客户端心跳处理器。
 *
 * 当连接在一段时间内没有写请求时，
 * 这个 handler 会主动发送心跳包，
 * 用来保持长连接活性并帮助探测对端状态。
 */
@Slf4j
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    /**
     * 监听空闲事件。
     *
     * 当前实现只在 WRITER_IDLE 时发送心跳，
     * 因为这说明这段时间没有正常业务请求出站。
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.WRITER_IDLE) {
                log.debug("Writer idle detected, sending heartbeat");
                sendHeartbeat(ctx);
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * 构造并发送一条心跳请求。
     *
     * 心跳同样复用统一 RPC 协议，只是 messageType 不同。
     */
    private void sendHeartbeat(ChannelHandlerContext ctx) {
        try {
            long requestId = System.nanoTime();

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
