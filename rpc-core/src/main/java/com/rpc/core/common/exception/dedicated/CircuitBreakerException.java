package com.rpc.core.common.exception.dedicated;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;

/**
 * 熔断器拒绝调用时抛出的专用异常。
 *
 * 所处阶段：consumer 侧服务级熔断或实例级熔断判断阶段。
 * 主要职责：用稳定的 ErrorCode 和更细的 Reason 告诉重试、降级、日志模块当前为什么没有继续发起请求。
 */
public class CircuitBreakerException extends RpcException {
    /**
     * 熔断拒绝原因。
     *
     * ALL_INSTANCES_OPEN 表示服务或全部实例不可用；
     * HALF_OPEN_PROBE_EXHAUSTED 表示半开探测名额已用完，适合让重试策略换下一个实例或稍后再试。
     */
    public enum Reason {
        ALL_INSTANCES_OPEN,
        HALF_OPEN_PROBE_EXHAUSTED
    }

    /** 细分拒绝原因，供重试策略和面试讲解区分不同熔断路径。 */
    private final Reason reason;

    /**
     * 创建默认“全部实例熔断”的异常。
     */
    public CircuitBreakerException(String serviceName) {
        this(serviceName, Reason.ALL_INSTANCES_OPEN);
    }

    /**
     * 创建指定原因的熔断异常。
     *
     * 注意事项：异常码固定为 CIRCUIT_BREAKER_OPEN，原因通过 reason 字段进一步表达。
     */
    public CircuitBreakerException(String serviceName, Reason reason) {
        super(
                ErrorCode.CIRCUIT_BREAKER_OPEN,
                buildMessage(serviceName, reason)
        );
        this.reason = reason;
    }

    /**
     * 获取熔断拒绝原因。
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * 生成适合日志和异常传播的错误信息。
     */
    private static String buildMessage(String serviceName, Reason reason) {
        if (reason == Reason.HALF_OPEN_PROBE_EXHAUSTED) {
            return "Circuit breaker half-open probes exhausted for service [" + serviceName + "]";
        }
        return "Circuit breaker is open for service [" + serviceName + "]";
    }
}
