package com.rpc.core.transport.netty.server.handler;

import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.transport.server.RpcRequestProcessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务端请求处理 handler。
 *
 * 这个 handler 不直接处理业务方法，
 * 而是把已经完成协议解码的 RpcMessage 交给 RpcRequestProcessor 处理，
 * 自己只负责衔接“收到消息 -> 处理 -> 回写响应”。
 */
@Slf4j
public class RpcRequestHandler extends ChannelInboundHandlerAdapter {
    /** 请求处理器，当前通常是 RpcRequestDispatcher。 */
    private final RpcRequestProcessor requestProcessor;

    public RpcRequestHandler(RpcRequestProcessor requestProcessor) {
        this.requestProcessor = requestProcessor;
    }

    /**
     * 处理入站消息。
     *
     * 只接收 RpcMessage，非 RPC 消息会继续向后传播；
     * 处理器返回的响应不为空时，再回写给客户端。
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof RpcMessage rpcMessage)) {
            log.warn("Received non-RPC message: {}", msg.getClass().getName());
            ctx.fireChannelRead(msg);
            return;
        }

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
