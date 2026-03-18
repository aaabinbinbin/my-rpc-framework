package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 心跳消息（用于保持连接活跃）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeartbeat {
    /** 请求 ID（用于匹配请求和响应） */
    private long requestId;
    /** 时间戳（仅响应时需要） */
    private long timestamp;
    /**
     * 创建心跳请求
     */
    public static RpcHeartbeat createRequest(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .build();
    }
    /**
     * 创建心跳响应
     */
    public static RpcHeartbeat createResponse(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
