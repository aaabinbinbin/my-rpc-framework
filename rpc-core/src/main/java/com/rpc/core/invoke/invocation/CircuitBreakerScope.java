package com.rpc.core.invoke.invocation;

/**
 * consumer 侧熔断统计粒度。
 *
 * 所处阶段：InvocationOptionsResolver 合并全局和方法级配置后，ConsumerCircuitBreakerFilter 选择熔断 key 时使用。
 * 主要职责：决定熔断器按服务整体统计，还是按服务方法分别统计。
 */
public enum CircuitBreakerScope {
    /** 服务级熔断，同一个服务下所有方法共享失败统计，适合快速保护整体不可用服务。 */
    SERVICE,
    /** 方法级熔断，不同方法隔离统计，适合读写方法稳定性差异较大的场景。 */
    METHOD;

    /**
     * 从配置字符串解析熔断粒度。
     *
     * 边界处理：空值或未知值默认 SERVICE，保证错误配置不会把服务切到更细粒度后失去保护效果。
     */
    public static CircuitBreakerScope from(String value) {
        if (value == null || value.isBlank()) {
            return SERVICE;
        }
        return switch (value.trim().toUpperCase().replace("-", "_")) {
            case "METHOD" -> METHOD;
            default -> SERVICE;
        };
    }
}

