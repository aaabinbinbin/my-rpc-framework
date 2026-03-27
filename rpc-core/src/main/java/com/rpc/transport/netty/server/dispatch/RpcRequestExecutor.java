package com.rpc.transport.netty.server.dispatch;

import com.rpc.metrics.ServiceMetrics;
import com.rpc.metrics.ServiceMetricsManager;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.registry.LocalRegistry;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

@Slf4j
public class RpcRequestExecutor {
    private final LocalRegistry localRegistry;
    private final ServiceMetricsManager metricsManager;

    public RpcRequestExecutor(LocalRegistry localRegistry) {
        this.localRegistry = localRegistry;
        this.metricsManager = ServiceMetricsManager.getInstance();
    }

    public RpcResponse execute(RpcRequest rpcRequest) {
        String serviceName = rpcRequest.getServiceName();
        ServiceMetrics metrics = metricsManager.get(serviceName);

        try {
            Object serviceBean = localRegistry.getService(serviceName);
            Method method = serviceBean.getClass().getMethod(
                    rpcRequest.getMethodName(),
                    rpcRequest.getParameterTypes()
            );
            Object result = method.invoke(serviceBean, rpcRequest.getParameters());

            if (metrics != null) {
                metrics.recordSuccess();
            }

            log.debug("RPC 调用成功: {}.{}", serviceName, rpcRequest.getMethodName());
            return RpcResponse.success(result, rpcRequest.getRequestId());
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordFailure();
            }

            log.error("RPC 调用失败: {}.{}", serviceName, rpcRequest.getMethodName(), e);
            return RpcResponse.fail(500, e.getMessage(), rpcRequest.getRequestId());
        }
    }
}
