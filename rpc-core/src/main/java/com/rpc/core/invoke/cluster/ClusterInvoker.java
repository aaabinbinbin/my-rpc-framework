package com.rpc.core.invoke.cluster;

import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;

/**
 * consumer 侧集群调用抽象。
 *
 * 所处阶段：过滤器链完成服务级处理后，进入负载均衡和真实传输调用之前。
 * 主要职责：封装 fail-fast、fail-over 等多实例调用策略。
 */
public interface ClusterInvoker {
    /**
     * 执行集群调用。
     *
     * 注意事项：transportInvoker 负责单次指定实例的真实发送，本接口负责决定是否换实例、是否重试。
     */
    RpcResponse invoke(RpcRequest request, RpcTransportInvoker transportInvoker) throws Exception;
}

