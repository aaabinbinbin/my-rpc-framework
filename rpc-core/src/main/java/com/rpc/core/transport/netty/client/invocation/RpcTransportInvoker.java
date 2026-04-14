package com.rpc.core.transport.netty.client.invocation;

import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;

import java.net.InetSocketAddress;

/**
 * 指定实例的真实传输调用函数。
 *
 * 所处阶段：ClusterInvoker 已选定某个 provider 地址后。
 * 主要职责：把“对某个地址发送请求”抽象为函数，便于 fail-over 策略在多个实例之间重试。
 */
@FunctionalInterface
public interface RpcTransportInvoker {
    /**
     * 向指定 provider 地址发送请求。
     *
     * 注意事项：该方法只代表单次 attempt，服务级重试和实例选择由上层 ClusterInvoker 控制。
     */
    RpcResponse invoke(RpcRequest rpcRequest, InetSocketAddress address) throws Exception;
}

