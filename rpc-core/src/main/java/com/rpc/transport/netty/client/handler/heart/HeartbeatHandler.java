package com.rpc.transport.netty.client.handler.heart;

import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcHeartbeat;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 心跳处理器
 *
 * 职责：
 * 1. 监听 IdleStateEvent 事件
 * 2. 检测到写空闲时发送心跳
 * 3. 构建心跳消息并发送
 */
@Slf4j
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;

            // 写空闲时，发送心跳
            if (event.state() == IdleState.WRITER_IDLE) {
                log.debug("发送心跳");
                sendHeartbeat(ctx);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 发送心跳
     */
    private void sendHeartbeat(ChannelHandlerContext ctx) {
        try {
            // 1. 生成请求 ID（使用时间戳保证唯一性）
            long requestId = System.nanoTime();

            // 2. 构建消息头
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) 0)  // 心跳消息不需要序列化
                    .messageType(RpcMessageType.HEARTBEAT_REQUEST.getCode())
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();

            // 3. 构建消息体
            RpcHeartbeat heartbeat = RpcHeartbeat.createRequest(requestId);

            // 4. 组装消息
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(heartbeat);

            // 5. 发送心跳
            ctx.writeAndFlush(message)
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            log.warn("发送心跳失败：{}", future.cause().getMessage());
                        } else {
                            log.debug("心跳发送成功，requestId: {}", requestId);
                        }
                    });

        } catch (Exception e) {
            log.error("构建心跳消息失败", e);
        }
    }
}
