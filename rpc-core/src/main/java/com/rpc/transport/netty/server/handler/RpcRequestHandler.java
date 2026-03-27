package com.rpc.transport.netty.server.handler;

import com.rpc.protocol.RpcMessage;
import com.rpc.transport.server.RpcRequestProcessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcRequestHandler extends ChannelInboundHandlerAdapter {
    private final RpcRequestProcessor requestProcessor;

    public RpcRequestHandler(RpcRequestProcessor requestProcessor) {
        this.requestProcessor = requestProcessor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof RpcMessage)) {
            log.warn("收到非 RPC 消息类型: {}", msg.getClass().getName());
            ctx.fireChannelRead(msg);
            return;
        }

        RpcMessage response = requestProcessor.process((RpcMessage) msg);
        if (response == null) {
            return;
        }

        ctx.writeAndFlush(response)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("发送响应失败", future.cause());
                    }
                });
    }
}
