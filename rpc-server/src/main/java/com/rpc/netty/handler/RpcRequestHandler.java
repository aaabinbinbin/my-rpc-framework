package com.rpc.netty.handler;

import com.rpc.protocol.*;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.registry.LocalRegistry;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * RPC 请求处理器
 * 处理客户端的远程调用请求
 */
@Slf4j
public class RpcRequestHandler extends SimpleChannelInboundHandler<RpcMessage> {
    // 服务注册表
    private final LocalRegistry localRegistry;

    public RpcRequestHandler(LocalRegistry localRegistry) {
        this.localRegistry = localRegistry;
    }
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) throws Exception {
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
            // 1. 获取服务实现类
            Class<?> serviceClass = localRegistry.getService(rpcRequest.getServiceName());
            // 2. 创建服务实例（实际应该用单例或 Spring 管理）
            Object serviceBean = serviceClass.getDeclaredConstructor().newInstance();
            // 3. 获取方法
            Method method = serviceClass.getMethod(rpcRequest.getMethodName(),
                    rpcRequest.getParameterTypes());
            // 4. 反射调用方法
            Object result = method.invoke(serviceBean, rpcRequest.getParameters());
            // 5. 构建成功响应
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

        // 3. 发送消息
        ctx.writeAndFlush(responseMessage)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("发送响应失败", future.cause());
                    }
                });
    }
}
