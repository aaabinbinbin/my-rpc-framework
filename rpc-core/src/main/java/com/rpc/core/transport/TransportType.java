package com.rpc.core.transport;

/**
 * 框架支持的传输协议类型。
 *
 * 所处阶段：配置加载完成后，传输工厂根据该枚举创建客户端或服务端实现。
 * 注意事项：NETTY 是默认高性能实现；SOCKET 保留为 legacy 路径，主要用于兼容和对比测试。
 */
public enum TransportType {
    /** 基于 Netty 的主传输实现，支持连接池、心跳、重连、pending 请求管理等能力。 */
    NETTY,
    /** 基于 JDK Socket 的 legacy 实现，保留兼容，不建议作为高并发首选。 */
    SOCKET;

    /**
     * 从配置字符串解析传输类型。
     *
     * 边界处理：配置为空时默认使用 NETTY；非法值交给 valueOf 抛出异常，便于启动阶段暴露错误配置。
     */
    public static TransportType from(String value) {
        if (value == null || value.isBlank()) {
            return NETTY;
        }
        return TransportType.valueOf(value.trim().toUpperCase());
    }
}

