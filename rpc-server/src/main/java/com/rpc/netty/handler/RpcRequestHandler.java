package com.rpc.netty.handler;

import com.rpc.protocol.*;
import com.rpc.protocol.codec.RpcProtocolEncoder;
import com.rpc.registry.LocalRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * RPC 请求处理器
 * 处理客户端的远程调用请求
 */
@Slf4j
public class RpcRequestHandler extends ChannelInboundHandlerAdapter {
    // 服务注册表
    private final LocalRegistry localRegistry;

    public RpcRequestHandler(LocalRegistry localRegistry) {
        this.localRegistry = localRegistry;
    }
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof RpcMessage)) {
            log.warn("收到非 RPC 消息类型：{}", msg.getClass().getName());
            ctx.fireChannelRead(msg);
            return;
        }

        RpcMessage message = (RpcMessage) msg;
        RpcHeader header = message.getHeader();

        // 1. 根据消息类型处理
        if (header.getMessageType() == RpcMessageType.REQUEST) {
            handleRequest(ctx, message);
        } else if (header.getMessageType() == RpcMessageType.HEARTBEAT_REQUEST) {
            handleHeartbeat(ctx, message);
        } else {
            log.warn("不支持的消息类型：{}", header.getMessageType());
        }
    }

    /**
     * 处理 RPC 请求
     */
    private void handleRequest(ChannelHandlerContext ctx, RpcMessage requestMessage) {
        RpcHeader requestHeader = requestMessage.getHeader();
        RpcRequest rpcRequest = (RpcRequest) requestMessage.getBody();

        log.info("收到 RPC 请求：{}.{}",
                rpcRequest.getServiceName(), rpcRequest.getMethodName());

        RpcResponse rpcResponse;
        try {
            // 1. 获取服务实现实例（单例）
            Object serviceBean = localRegistry.getService(rpcRequest.getServiceName());
            log.info("服务实例hash: {}", serviceBean.hashCode());
            // 2. 获取方法
            Method method = serviceBean.getClass().getMethod(rpcRequest.getMethodName(),
                    rpcRequest.getParameterTypes());
            // 3. 反射调用方法
            Object result = method.invoke(serviceBean, rpcRequest.getParameters());
            // 4. 构建成功响应
            rpcResponse = RpcResponse.success(result, rpcRequest.getRequestId());
            log.debug("RPC 调用成功：{}", result);

        } catch (Exception e) {
            log.error("RPC 调用失败", e);
            // 构建失败响应
            rpcResponse = RpcResponse.fail(500, e.getMessage(), rpcRequest.getRequestId());
        }
        // 发送响应
        sendMessage(ctx, rpcResponse, requestHeader);
    }

    /**
     * 处理心跳请求
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, RpcMessage requestMessage) {
        log.debug("收到心跳请求");
        RpcResponse heartbeatResponse = RpcResponse.success("PONG",
                String.valueOf(requestMessage.getHeader().getRequestId()));
        sendMessage(ctx, heartbeatResponse, requestMessage.getHeader());
    }

    /**
     * 发送响应消息
     */
    private void sendMessage(ChannelHandlerContext ctx, Object body, RpcHeader requestHeader) {
        // 1. 构建响应头 - 消息体长度和校验字段会在编码阶段进行填充
        RpcHeader responseHeader = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(requestHeader.getSerializerType())
                .messageType(RpcMessageType.RESPONSE)
                .reserved((byte) 0)
                .requestId(requestHeader.getRequestId())
                .build();

        // 2. 构建响应消息
        RpcMessage responseMessage = new RpcMessage();
        responseMessage.setHeader(responseHeader);
        responseMessage.setBody(body);
        log.info("准备发送响应：requestId={}, 消息类型={}",
                responseHeader.getRequestId(), responseHeader.getMessageType());

        // 3. 写入 Pipeline - 让 RpcProtocolEncoder 自动编码
        ctx.writeAndFlush(responseMessage)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("发送响应失败", future.cause());
                    }
                });
    }
}
