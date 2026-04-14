package com.rpc.core.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM 进程内共享的 requestId 生成器。
 *
 * requestId 只要求在单 JVM 生命周期内唯一，
 * 因此使用无锁递增序列即可满足客户端请求响应匹配需求。
 */
public final class RequestIdGenerator {
    /** 当前 JVM 内单调递增的 requestId 序列。 */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** 工具类不允许实例化。 */
    private RequestIdGenerator() {
    }

    /**
     * 生成下一个 requestId。
     *
     * 所处阶段：consumer 侧一次真实网络 attempt 开始前。
     * 注意事项：该 ID 用于 pending 请求表和响应匹配，不承担全链路 traceId 的职责。
     */
    public static long nextId() {
        return SEQUENCE.incrementAndGet();
    }
}
