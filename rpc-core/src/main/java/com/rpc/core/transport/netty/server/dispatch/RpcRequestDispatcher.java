package com.rpc.core.transport.netty.server.dispatch;

import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcHeartbeat;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.RpcMessageType;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.transport.server.RpcRequestProcessor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcRequestDispatcher implements RpcRequestProcessor {
    private final RpcRequestExecutor requestExecutor;
    private final ServerLifecycle serverLifecycle;

    public RpcRequestDispatcher(RpcRequestExecutor requestExecutor, ServerLifecycle serverLifecycle) {
        this.requestExecutor = requestExecutor;
        this.serverLifecycle = serverLifecycle;
    }

    @Override
    public RpcMessage process(RpcMessage message) {
        RpcHeader header = message.getHeader();
        RpcMessageType messageType = RpcMessageType.fromCode(header.getMessageType());

        // dispatcher 是服务端第一层分流：
        // 心跳消息直接构造响应，业务消息才进入业务执行链。
        return switch (messageType) {
            case HEARTBEAT_REQUEST -> handleHeartbeatRequest(message);
            case REQUEST -> handleBusinessRequest(message);
            default -> null;
        };
    }

    private RpcMessage handleBusinessRequest(RpcMessage requestMessage) {
        RpcHeader requestHeader = requestMessage.getHeader();
        RpcRequest rpcRequest = (RpcRequest) requestMessage.getBody();

        RpcResponse rpcResponse;
        if (!serverLifecycle.isAcceptingRequests()) {
            // 优雅停机期间不再受理新业务请求，但会继续处理已经在途的请求。
            rpcResponse = RpcResponse.fail(503, "Server is shutting down", rpcRequest.getRequestId());
        } else {
            rpcResponse = requestExecutor.execute(rpcRequest);
        }
        return buildResponseMessage(rpcResponse, requestHeader);
    }

    private RpcMessage handleHeartbeatRequest(RpcMessage request) {
        RpcHeartbeat heartbeatRequest = (RpcHeartbeat) request.getBody();
        long requestId = heartbeatRequest.getRequestId();
        // 心跳响应沿用同一个 requestId，客户端就可以据此计算往返延迟。
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
    }

    private RpcMessage buildResponseMessage(Object body, RpcHeader requestHeader) {
        // 业务响应沿用请求头里的 serializerType，保证请求和响应的序列化方式一致。
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
        return responseMessage;
    }
}

