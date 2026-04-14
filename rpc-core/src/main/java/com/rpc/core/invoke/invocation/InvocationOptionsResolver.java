package com.rpc.core.invoke.invocation;

import com.rpc.core.protocol.message.RpcRequest;

/**
 * 调用选项解析器。
 *
 * 所处阶段：consumer 侧发起调用前，根据全局配置、方法级配置和请求 attachments 合并最终执行选项。
 * 主要职责：输出本次调用实际使用的超时、重试、集群策略、熔断粒度、序列化器和负载均衡器等参数。
 */
public interface InvocationOptionsResolver {
    /**
     * 解析某次请求的最终调用选项。
     *
     * 边界处理：请求没有方法级覆盖时应返回全局默认选项。
     */
    InvocationOptions resolve(RpcRequest request);
}

