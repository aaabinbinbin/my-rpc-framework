package com.rpc.transport.netty.server.dispatch;

import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcHeartbeat;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.transport.server.RpcRequestProcessor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcRequestDispatcher implements RpcRequestProcessor {
    private final RpcRequestExecutor requestExecutor;

    public RpcRequestDispatcher(RpcRequestExecutor requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    @Override
    public RpcMessage process(RpcMessage message) {
        RpcHeader header = message.getHeader();
        RpcMessageType messageType = RpcMessageType.fromCode(header.getMessageType());

        return switch (messageType) {
            case HEARTBEAT_REQUEST -> handleHeartbeatRequest(message);
            case REQUEST -> handleBusinessRequest(message);
            default -> {
                log.warn("未知消息类型: {}", messageType);
                yield null;
            }
        };
    }

    private RpcMessage handleBusinessRequest(RpcMessage requestMessage) {
        RpcHeader requestHeader = requestMessage.getHeader();
        RpcRequest rpcRequest = (RpcRequest) requestMessage.getBody();

        log.info("收到 RPC 请求: {}.{}", rpcRequest.getServiceName(), rpcRequest.getMethodName());
        RpcResponse rpcResponse = requestExecutor.execute(rpcRequest);
        return buildResponseMessage(rpcResponse, requestHeader);
    }

    private RpcMessage handleHeartbeatRequest(RpcMessage request) {
        try {
            RpcHeartbeat heartbeatRequest = (RpcHeartbeat) request.getBody();
            long requestId = heartbeatRequest.getRequestId();

            log.debug("收到心跳请求: requestId={}", requestId);
            RpcHeartbeat heartbeatResponse = RpcHeartbeat.createResponse(requestId);

            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) 0)
                    .messageType(RpcMessageType.HEARTBEAT_RESPONSE.getCode())
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();

            RpcMessage response = new RpcMessage();
            response.setHeader(header);
            response.setBody(heartbeatResponse);
            return response;
        } catch (Exception e) {
            log.error("处理心跳请求失败", e);
            return null;
        }
    }

    private RpcMessage buildResponseMessage(Object body, RpcHeader requestHeader) {
        RpcHeader responseHeader = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(requestHeader.getSerializerType())
                .messageType(RpcMessageType.RESPONSE.getCode())
                .reserved((byte) 0)
                .requestId(requestHeader.getRequestId())
                .build();

        RpcMessage responseMessage = new RpcMessage();
        responseMessage.setHeader(responseHeader);
        responseMessage.setBody(body);

        log.info("准备发送响应: requestId={}, messageType={}",
                responseHeader.getRequestId(), responseHeader.getMessageType());
        return responseMessage;
    }
}
