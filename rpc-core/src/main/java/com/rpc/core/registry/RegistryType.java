package com.rpc.core.registry;

/**
 * 注册中心类型枚举。
 *
 * 所处阶段：框架启动时由配置绑定层解析，随后交给 ServiceRegistryFactory 创建注册中心实现。
 * 当前项目主实现为 ZooKeeper，后续扩展 Nacos、Redis、Consul 时可在这里增加枚举值。
 */
public enum RegistryType {
    /** 基于 ZooKeeper 的服务注册与发现实现。 */
    ZOOKEEPER;

    /**
     * 从配置字符串解析注册中心类型。
     *
     * 边界处理：配置为空时默认使用 ZOOKEEPER；非法值直接抛出 IllegalArgumentException，让启动失败更早暴露。
     */
    public static RegistryType from(String value) {
        if (value == null || value.isBlank()) {
            return ZOOKEEPER;
        }
        return RegistryType.valueOf(value.trim().toUpperCase());
    }
}

