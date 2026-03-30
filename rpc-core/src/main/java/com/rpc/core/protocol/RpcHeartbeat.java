package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 传输层使用的心跳载荷。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeartbeat {
    private long requestId;
    private long timestamp;

    public static RpcHeartbeat createRequest(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .build();
    }

    public static RpcHeartbeat createResponse(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
