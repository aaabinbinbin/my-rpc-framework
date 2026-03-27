package com.rpc.transport.netty.server.dispatch;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.registry.LocalRegistry;
import com.rpc.transport.netty.server.statistics.ServiceStatistics;
import com.rpc.transport.netty.server.statistics.StatisticsManager;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

@Slf4j
public class RpcRequestExecutor {
    private final LocalRegistry localRegistry;
    private final StatisticsManager statisticsManager;

    public RpcRequestExecutor(LocalRegistry localRegistry) {
        this.localRegistry = localRegistry;
        this.statisticsManager = StatisticsManager.getInstance();
    }

    public RpcResponse execute(RpcRequest rpcRequest) {
        String serviceName = rpcRequest.getServiceName();
        long startTime = System.currentTimeMillis();
        ServiceStatistics statistics = statisticsManager.getStatistics(serviceName);

        try {
            if (statistics != null) {
                statistics.recordStart();
            }

            Object serviceBean = localRegistry.getService(serviceName);
            Method method = serviceBean.getClass().getMethod(
                    rpcRequest.getMethodName(),
                    rpcRequest.getParameterTypes()
            );
            Object result = method.invoke(serviceBean, rpcRequest.getParameters());

            if (statistics != null) {
                statistics.recordSuccess(startTime);
            }

            log.debug("RPC 调用成功: {}.{}", serviceName, rpcRequest.getMethodName());
            return RpcResponse.success(result, rpcRequest.getRequestId());
        } catch (Exception e) {
            if (statistics != null) {
                statistics.recordFailed(startTime);
            }

            log.error("RPC 调用失败: {}.{}", serviceName, rpcRequest.getMethodName(), e);
            return RpcResponse.fail(500, e.getMessage(), rpcRequest.getRequestId());
        }
    }
}
