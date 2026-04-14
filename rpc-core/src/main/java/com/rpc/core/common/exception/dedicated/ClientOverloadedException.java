package com.rpc.core.common.exception.dedicated;

/**
 * 客户端侧自我保护触发时抛出的异常。
 *
 * 所处阶段：consumer 侧真正写网络请求前，连接池或 pending 请求管理发现本地资源达到上限。
 * 主要职责：区分“远端服务失败”和“本地客户端过载”，便于异常映射为 CLIENT_BUSY。
 */
public class ClientOverloadedException extends IllegalStateException {
    /**
     * 客户端过载原因。
     *
     * PENDING_REQUEST_LIMIT_EXCEEDED：未完成请求数达到上限；
     * TOTAL_CONNECTION_LIMIT_EXCEEDED：连接池总连接预算达到上限。
     */
    public enum Reason {
        PENDING_REQUEST_LIMIT_EXCEEDED,
        TOTAL_CONNECTION_LIMIT_EXCEEDED
    }

    /** 具体过载原因，供日志、监控和异常映射层使用。 */
    private final Reason reason;

    /**
     * 创建客户端过载异常。
     */
    public ClientOverloadedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 获取客户端过载原因。
     */
    public Reason getReason() {
        return reason;
    }
}
