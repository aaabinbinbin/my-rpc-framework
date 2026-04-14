package com.rpc.core.transport;

import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;

/**
 * consumer 侧传输层统一抽象。
 *
 * 所处阶段：代理层、集群容错和过滤器完成后，真正进入网络发送前。
 * 主要职责：屏蔽 Netty、Socket 等传输实现差异，对上层只暴露“发送请求并返回响应”的语义。
 *
 * 注意事项：
 * 1. 实现类需要负责连接管理、超时管理、请求响应匹配和资源释放。
 * 2. sendRequest 抛出的底层异常应尽量映射为 RpcException 或可被 RpcExceptionMapper 识别的异常。
 */
public interface RpcTransport extends AutoCloseable {
    /**
     * 发送一次 RPC 请求并等待响应。
     *
     * 边界处理：请求超时、连接不可用、服务端异常等情况由实现类抛出异常或返回错误响应，上层再决定是否重试、降级或熔断。
     */
    RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception;

    /**
     * 关闭传输客户端并释放网络资源。
     *
     * 注意事项：实现应保证幂等，避免 Spring 容器关闭、用户手动关闭和异常清理重复触发时出现二次释放问题。
     */
    @Override
    void close();
}

