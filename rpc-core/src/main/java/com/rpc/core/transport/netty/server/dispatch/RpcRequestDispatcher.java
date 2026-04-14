package com.rpc.core.transport.netty.server.dispatch;

import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcHeartbeat;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.transport.server.RpcRequestProcessor;
import lombok.extern.slf4j.Slf4j;

/**
 * provider 侧请求分发器。
 *
 * 这个类位于服务端入口较靠前的位置，
 * 负责在消息刚进入 provider 时先按消息类型做第一次分流：
 * - 心跳请求直接构造心跳响应。
 * - 业务请求再交给 requestExecutor 继续处理。
 *
 * 它本身不直接执行业务方法，而是承担“入口分流”和“响应包装”的职责。
 */
@Slf4j
public class RpcRequestDispatcher implements RpcRequestProcessor {
    /** 业务请求执行器，负责真正进入 provider 本地执行链。 */
    private final RpcRequestExecutor requestExecutor;

    /** 服务端生命周期状态，用于优雅停机时判断是否还接收新请求。 */
    private final ServerLifecycle serverLifecycle;

    public RpcRequestDispatcher(RpcRequestExecutor requestExecutor, ServerLifecycle serverLifecycle) {
        this.requestExecutor = requestExecutor;
        this.serverLifecycle = serverLifecycle;
    }

    /**
     * provider 入口总分发方法。
     *
     * 这里先读取协议头里的 messageType，
     * 再决定这条消息应该进入心跳分支还是业务分支。
     */
    @Override
    public RpcMessage process(RpcMessage message) {
        RpcHeader header = message.getHeader();
        RpcMessageType messageType = RpcMessageType.fromCode(header.getMessageType());

        return switch (messageType) {
            case HEARTBEAT_REQUEST -> handleHeartbeatRequest(message);
            case REQUEST -> handleBusinessRequest(message);
            default -> null;
        };
    }

    /**
     * 处理普通业务请求。
     *
     * 如果服务端正处于停止接收新请求的阶段，就直接返回 503；
     * 否则交给 requestExecutor 去完成本地服务定位和方法执行。
     */
    private RpcMessage handleBusinessRequest(RpcMessage requestMessage) {
        RpcHeader requestHeader = requestMessage.getHeader();
        RpcRequest rpcRequest = (RpcRequest) requestMessage.getBody();

        serverLifecycle.incrementActiveRequests();
        try {
            RpcResponse rpcResponse;
            if (!serverLifecycle.isAcceptingRequests()) {
                rpcResponse = RpcResponse.fail(503, "Server is shutting down", rpcRequest.getRequestId());
            } else {
                rpcResponse = requestExecutor.execute(rpcRequest);
            }
            return buildResponseMessage(rpcResponse, requestHeader);
        } finally {
            serverLifecycle.decrementActiveRequests();
        }
    }

    /**
     * 处理心跳请求。
     *
     * 心跳不需要进入业务执行链，
     * 只需要直接构造一个带相同 requestId 的心跳响应即可。
     */
    private RpcMessage handleHeartbeatRequest(RpcMessage request) {
        RpcHeartbeat heartbeatRequest = (RpcHeartbeat) request.getBody();
        long requestId = heartbeatRequest.getRequestId();
        RpcHeartbeat heartbeatResponse = RpcHeartbeat.createResponse(requestId, heartbeatRequest.getTimestamp());

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

    /**
     * 构造业务响应消息。
     *
     * 响应沿用请求里的 serializerType 和 requestId，
     * 这样 consumer 才能使用正确的序列化器解码，并把响应匹配回对应请求。
     */
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
        return responseMessage;
    }
}
