package com.rpc.core.transport.netty.server.handler;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.server.RpcRequestProcessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * provider 侧 RPC 入站请求处理器。
 *
 * 所处阶段：Netty 已经完成协议解码，当前 handler 收到的是 RpcMessage。
 * 主要职责：
 * - 识别业务请求和非业务请求。
 * - 业务请求投递到 provider 业务线程池，避免阻塞 Netty IO 线程。
 * - 心跳等非业务消息直接处理，避免业务线程池满时误判连接异常。
 * - 业务线程池拒绝时返回 SERVER_BUSY，给 consumer 明确的过载语义。
 */
@Slf4j
public class RpcRequestHandler extends ChannelInboundHandlerAdapter {
    private final RpcRequestProcessor requestProcessor;
    private final Executor requestExecutor;

    public RpcRequestHandler(RpcRequestProcessor requestProcessor, Executor requestExecutor) {
        this.requestProcessor = requestProcessor;
        this.requestExecutor = requestExecutor;
    }

    /** Netty 入站入口；只在这里做轻量分发，不直接执行业务方法。 */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof RpcMessage rpcMessage)) {
            log.warn("Received non-RPC message: {}", msg == null ? "null" : msg.getClass().getName());
            ctx.fireChannelRead(msg);
            return;
        }

        if (!isBusinessRequest(rpcMessage)) {
            processAndWrite(ctx, rpcMessage);
            return;
        }

        try {
            requestExecutor.execute(() -> processAndWrite(ctx, rpcMessage));
        } catch (RejectedExecutionException e) {
            log.warn("Biz executor is saturated, return busy response");
            writeResponse(ctx, buildFailureResponse(rpcMessage, ErrorCode.SERVER_BUSY, "Server busy"));
        } catch (RuntimeException e) {
            log.error("Failed to dispatch RPC request", e);
            writeResponse(ctx, buildFailureResponse(rpcMessage, ErrorCode.SERVER_ERROR, "Server request dispatch failed"));
        }
    }

    /** 只有普通 REQUEST 才进入业务线程池；心跳、响应等非业务消息保持轻量处理。 */
    private boolean isBusinessRequest(RpcMessage rpcMessage) {
        return RpcMessageType.fromCode(rpcMessage.getHeader().getMessageType()) == RpcMessageType.REQUEST;
    }

    /** 调用下游请求处理器并写回响应；异常会转换成 SERVER_ERROR 响应，避免客户端一直等待。 */
    private void processAndWrite(ChannelHandlerContext ctx, RpcMessage rpcMessage) {
        try {
            RpcMessage response = requestProcessor.process(rpcMessage);
            if (response == null) {
                return;
            }
            writeResponse(ctx, response);
        } catch (Exception e) {
            log.error("Failed to process RPC request", e);
            writeResponse(ctx, buildFailureResponse(rpcMessage, ErrorCode.SERVER_ERROR, e.getMessage()));
        }
    }

    /** 写响应前检查 channel 状态，避免在已关闭连接上继续写出。 */
    private void writeResponse(ChannelHandlerContext ctx, RpcMessage response) {
        if (response == null || !ctx.channel().isActive()) {
            return;
        }
        ctx.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.error("Failed to write RPC response", future.cause());
            }
        });
    }

    /** 构造框架级失败响应；serializerType 和 requestId 沿用请求头，保证客户端能正确解码和匹配。 */
    private RpcMessage buildFailureResponse(RpcMessage requestMessage, ErrorCode errorCode, String message) {
        RpcHeader requestHeader = requestMessage.getHeader();
        RpcRequest rpcRequest = requestMessage.getBody() instanceof RpcRequest request ? request : null;
        String requestId = rpcRequest != null ? rpcRequest.getRequestId() : String.valueOf(requestHeader.getRequestId());
        RpcResponse response = RpcResponse.fail(errorCode.getCode(), message, requestId);

        RpcHeader responseHeader = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(requestHeader.getSerializerType())
                .messageType(RpcMessageType.RESPONSE.getCode())
                .reserved((byte) 0)
                .requestId(requestHeader.getRequestId())
                .build();

        return RpcMessage.builder()
                .header(responseHeader)
                .body(response)
                .build();
    }
}
