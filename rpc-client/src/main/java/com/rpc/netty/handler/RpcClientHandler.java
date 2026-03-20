package com.rpc.netty.handler;

import com.rpc.manager.RequestManager;
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
        // 1. 根据消息类型处理
        if (message.getHeader().getMessageType() == RpcMessageType.RESPONSE) {
            handleResponse(message);
        } else if (message.getHeader().getMessageType() == RpcMessageType.HEARTBEAT_RESPONSE) {
            log.debug("收到心跳响应");
        } else {
            log.warn("不支持的消息类型：{}", message.getHeader().getMessageType());
        }
    }

    /**
     * 处理响应
     */
    private void handleResponse(RpcMessage message) {
        RpcResponse response = (RpcResponse) message.getBody();
        // 通知请求管理器完成 Future
        requestManager.completeResponse(response);
    }

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
        // TODO: 实现心跳消息发送
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("已连接到服务器：{}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("与服务器断开连接：{}", ctx.channel().remoteAddress());
        // 失败所有待处理请求
        // TODO: 实现失败逻辑
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端异常", cause);
        ctx.close();
    }
}
