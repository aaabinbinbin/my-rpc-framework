package com.rpc.transport.netty.server.handler;

import com.rpc.protocol.*;
import com.rpc.registry.LocalRegistry;
import com.rpc.transport.netty.server.statistics.ServiceStatistics;
import com.rpc.transport.netty.server.statistics.StatisticsManager;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
    private final StatisticsManager statisticsManager;

    public RpcRequestHandler(LocalRegistry localRegistry) {
        this.localRegistry = localRegistry;
        this.statisticsManager = StatisticsManager.getInstance();
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
        RpcMessageType messageType = RpcMessageType.fromCode(header.getMessageType());

        switch (messageType) {
            case HEARTBEAT_REQUEST:
                // 处理心跳请求
                handleHeartbeatRequest(ctx, message);
                break;
            case REQUEST:
                // 处理业务请求
                handleBusinessRequest(ctx, message);
                break;
            default:
                log.warn("未知消息类型：{}", messageType);
        }
    }

    /**
     * 处理 RPC 请求
     */
    private void handleBusinessRequest(ChannelHandlerContext ctx, RpcMessage requestMessage) {
        RpcHeader requestHeader = requestMessage.getHeader();
        RpcRequest rpcRequest = (RpcRequest) requestMessage.getBody();

        log.info("收到 RPC 请求：{}.{}",
                rpcRequest.getServiceName(), rpcRequest.getMethodName());

        RpcResponse rpcResponse;
        long startTime = System.currentTimeMillis();
        try {
            // 1. 获取服务统计信息
            ServiceStatistics statistics = statisticsManager.getStatistics(
                    rpcRequest.getServiceName()
            );
            if (statistics != null) {
                statistics.recordStart();
            }
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
            // 6. 记录成功统计
            if (statistics != null) {
                statistics.recordSuccess(startTime);
            }
        } catch (Exception e) {
            log.error("RPC 调用失败", e);
            // 构建失败响应
            rpcResponse = RpcResponse.fail(500, e.getMessage(), rpcRequest.getRequestId());
            // 记录失败统计
            ServiceStatistics statistics = statisticsManager.getStatistics(
                    rpcRequest.getServiceName()
            );
            if (statistics != null) {
                statistics.recordFailed(startTime);
            }
        }
        // 发送响应
        sendMessage(ctx, rpcResponse, requestHeader);
    }

    /**
     * 处理心跳请求
     */
    private void handleHeartbeatRequest(ChannelHandlerContext ctx, RpcMessage request) {
        try {
            RpcHeartbeat heartbeatRequest = (RpcHeartbeat) request.getBody();
            long requestId = heartbeatRequest.getRequestId();

            log.debug("收到心跳请求：requestId={}", requestId);

            // 构建心跳响应
            RpcHeartbeat heartbeatResponse = RpcHeartbeat.createResponse(requestId);

            // 构建响应消息头
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) 0)
                    .messageType(RpcMessageType.HEARTBEAT_RESPONSE.getCode())
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();

            // 组装响应消息
            RpcMessage response = new RpcMessage();
            response.setHeader(header);
            response.setBody(heartbeatResponse);

            // 发送响应
            ctx.writeAndFlush(response)
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            log.warn("发送心跳响应失败", future.cause());
                        }
                    });

        } catch (Exception e) {
            log.error("处理心跳请求失败", e);
        }
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
                .messageType(RpcMessageType.RESPONSE.getCode())
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
