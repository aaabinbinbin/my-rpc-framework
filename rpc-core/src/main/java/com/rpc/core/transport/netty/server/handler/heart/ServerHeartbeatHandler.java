package com.rpc.core.transport.netty.server.handler.heart;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务端空闲连接监控器。
 */
@Slf4j
public class ServerHeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event) {
            IdleState state = event.state();
            switch (state) {
                case READER_IDLE:
                    // 读空闲说明对端长时间没有任何输入，通常意味着连接可能已异常。
                    log.warn("Reader idle on server channel: {}", ctx.channel().remoteAddress());
                    break;
                case WRITER_IDLE:
                    log.debug("Writer idle on server channel");
                    break;
                case ALL_IDLE:
                    // 全空闲时直接关闭连接，避免长期挂着无用 channel。
                    log.warn("Closing all-idle server channel: {}", ctx.channel().remoteAddress());
                    ctx.close();
                    break;
                default:
                    super.userEventTriggered(ctx, evt);
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("Server channel inactive: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Server heartbeat handler caught exception", cause);
        ctx.close();
    }
}
