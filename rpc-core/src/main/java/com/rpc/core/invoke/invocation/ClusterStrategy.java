package com.rpc.core.invoke.invocation;

/**
 * consumer 侧集群调用策略。
 *
 * 所处阶段：负载均衡选址和真实网络发送之间。
 * 主要职责：决定单次服务调用失败后是快速失败，还是换实例重试。
 */
public enum ClusterStrategy {
    /** 快速失败，只调用一次，适合非幂等写操作或强一致业务。 */
    FAIL_FAST,
    /** 故障转移，失败后换实例重试，适合幂等读操作或可容忍重复执行的请求。 */
    FAIL_OVER;

    /**
     * 从配置字符串解析集群策略。
     *
     * 边界处理：兼容 fail-fast、fail_fast、FAILFAST 等写法；空值或未知值默认 FAIL_OVER。
     */
    public static ClusterStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return FAIL_OVER;
        }

        String normalized = value.trim()
                .replace("-", "")
                .replace("_", "")
                .toUpperCase();

        return switch (normalized) {
            case "FAILFAST" -> FAIL_FAST;
            case "FAILOVER" -> FAIL_OVER;
            default -> FAIL_OVER;
        };
    }
}

