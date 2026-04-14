package com.rpc.core.transport.netty.client.handler;

import com.rpc.core.protocol.message.RpcHeartbeat;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.netty.client.request.RequestManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty 客户端入站响应处理器。
 *
 * 所处阶段：客户端收到 provider 响应并完成协议解码后。
 * 主要职责：根据消息类型处理心跳响应或业务响应，并通过 RequestManager 完成 pending 请求。
 */
@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {
    /** pending 请求管理器，用于 requestId -> CompletableFuture 的响应匹配。 */
    private final RequestManager requestManager;

    /**
     * 创建客户端响应处理器。
     */
    public RpcClientHandler(RequestManager requestManager) {
        this.requestManager = requestManager;
    }

    /**
     * 分发入站消息。
     *
     * 边界处理：客户端只处理心跳响应和业务响应，其他类型记录告警并忽略。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        RpcMessageType messageType = RpcMessageType.fromCode(message.getHeader().getMessageType());
        switch (messageType) {
            case HEARTBEAT_RESPONSE:
                handleHeartbeatResponse(message);
                break;
            case RESPONSE:
                handleBusinessResponse(message);
                break;
            default:
                log.warn("Unsupported client message type: {}", messageType);
        }
    }

    /**
     * 处理业务响应并完成 pending future。
     */
    private void handleBusinessResponse(RpcMessage message) {
        RpcResponse response = (RpcResponse) message.getBody();
        requestManager.completeResponse(response);
    }

    /**
     * 处理心跳响应。
     */
    private void handleHeartbeatResponse(RpcMessage message) {
        RpcHeartbeat heartbeat = (RpcHeartbeat) message.getBody();
        long latency = System.currentTimeMillis() - heartbeat.getTimestamp();
        log.debug("Received heartbeat response requestId={}, latency={}ms",
                heartbeat.getRequestId(), latency);
    }

    /**
     * channel 失活时失败该 channel 上所有未完成请求。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        requestManager.failRequestsForChannel(
                ctx.channel(),
                new IllegalStateException("Channel inactive: " + ctx.channel().remoteAddress())
        );
        super.channelInactive(ctx);
    }

    /**
     * Netty pipeline 异常处理。
     *
     * 边界处理：先失败 pending 请求，再关闭 channel，避免调用方一直等待超时扫描。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        requestManager.failRequestsForChannel(ctx.channel(), cause);
        log.warn("Client handler caught exception on channel {}: {}",
                ctx.channel().remoteAddress(), cause.toString());
        log.debug("Client handler exception details", cause);
        ctx.close();
    }
}
