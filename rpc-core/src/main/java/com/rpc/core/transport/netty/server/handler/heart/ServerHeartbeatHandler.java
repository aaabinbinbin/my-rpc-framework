package com.rpc.core.transport.netty.server.handler.heart;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务端空闲连接监控 handler。
 *
 * 这个类不负责构造业务响应，而是负责在连接长时间空闲时做连接级处理，
 * 避免服务端长期挂着无意义的空闲 channel。
 */
@Slf4j
public class ServerHeartbeatHandler extends ChannelInboundHandlerAdapter {
    /**
     * 处理 Netty 空闲事件。
     *
     * 当前策略：
     * 1. 读空闲：记录告警，提示客户端可能异常。
     * 2. 写空闲：仅做调试日志。
     * 3. 全空闲：直接关闭连接。
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event) {
            IdleState state = event.state();
            switch (state) {
                case READER_IDLE:
                    log.warn("Reader idle on server channel: {}", ctx.channel().remoteAddress());
                    break;
                case WRITER_IDLE:
                    log.debug("Writer idle on server channel");
                    break;
                case ALL_IDLE:
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
