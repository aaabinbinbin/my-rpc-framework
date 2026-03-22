package com.rpc.transport.netty.server.handler.heart;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务端心跳处理器
 * 职责：
 * 1. 检测客户端连接状态
 * 2. 处理读空闲事件
 * 3. 关闭异常连接
 */
@Slf4j
public class ServerHeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
            throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;

            switch (event.state()) {
                case READER_IDLE:
                    // 读空闲，表示长时间未收到客户端数据
                    log.warn("客户端长时间未发送数据，可能已断开：{}",
                            ctx.channel().remoteAddress());
                    // 可以选择主动探测或关闭连接
                    // ctx.close();
                    break;

                case WRITER_IDLE:
                    // 服务端一般不需要主动发送心跳
                    log.debug("服务端写空闲");
                    break;

                case ALL_IDLE:
                    // 全空闲，连接可能已失效
                    log.warn("客户端连接全空闲，准备关闭连接：{}",
                            ctx.channel().remoteAddress());
                    ctx.close();
                    break;

                default:
                    super.userEventTriggered(ctx, evt);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("客户端断开连接：{}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        log.error("服务端心跳处理器异常", cause);
        ctx.close();
    }
}
