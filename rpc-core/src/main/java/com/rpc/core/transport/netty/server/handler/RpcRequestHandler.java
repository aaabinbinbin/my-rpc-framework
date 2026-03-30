package com.rpc.core.transport.netty.server.handler;

import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.transport.server.RpcRequestProcessor;
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
        if (!(msg instanceof RpcMessage rpcMessage)) {
            log.warn("Received non-RPC message: {}", msg.getClass().getName());
            ctx.fireChannelRead(msg);
            return;
        }

        // handler 本身不处理业务，只负责把解码后的 RpcMessage 交给 requestProcessor。
        RpcMessage response = requestProcessor.process(rpcMessage);
        if (response == null) {
            return;
        }

        ctx.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.error("Failed to write RPC response", future.cause());
            }
        });
    }
}
