package com.rpc.core.transport.netty.client.invocation;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.discovery.ServiceDirectory;
import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.loadbalance.factory.LoadBalancerFactory;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * consumer 侧服务地址解析器。
 *
 * 所处阶段：调用编排器已经决定要调用哪个 serviceName，
 * 当前类负责从服务目录拿实例快照，并结合负载均衡和实例级熔断选出一个 provider 地址。
 *
 * 注意事项：
 * - 这里不直接访问注册中心，而是读取 ServiceDirectory 的本地快照。
 * - select 后需要在调用结束时 release，用于 least-connections 等有状态负载均衡器释放计数。
 */
public class RpcServiceResolver {
    /** consumer 本地服务目录，屏蔽注册中心访问、缓存、订阅和失败回退细节。 */
    private final ServiceDirectory serviceDirectory;
    /** 默认负载均衡器，方法级配置为空时使用它。 */
    private final LoadBalancer loadBalancer;
    /** 熔断器管理器，用于实例级熔断过滤和半开探测控制。 */
    private final CircuitBreakerManager circuitBreakerManager;

    public RpcServiceResolver(ServiceDirectory serviceDirectory,
                              LoadBalancer loadBalancer,
                              CircuitBreakerManager circuitBreakerManager) {
        this.serviceDirectory = serviceDirectory;
        this.loadBalancer = loadBalancer;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    /** 使用默认负载均衡策略解析 provider 地址。 */
    public InetSocketAddress resolve(String serviceName) throws RpcException {
        return resolve(serviceName, null);
    }

    /**
     * 按指定负载均衡策略解析 provider 地址。
     *
     * 边界处理：
     * - 服务实例为空时直接抛 SERVICE_NOT_FOUND。
     * - 负载均衡选择时会跳过 OPEN 状态实例，并尊重 HALF_OPEN 探测名额。
     */
    public InetSocketAddress resolve(String serviceName, String loadBalancerName) throws RpcException {
        List<InetSocketAddress> addresses = serviceDirectory.getSnapshot(serviceName).getAddresses();
        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(ErrorCode.SERVICE_NOT_FOUND, "Service not found: " + serviceName);
        }
        InetSocketAddress address = resolveLoadBalancer(loadBalancerName)
                .selectWithCircuitBreaker(serviceName, addresses, circuitBreakerManager);
        serviceDirectory.rememberAddressService(address, serviceName);
        return address;
    }

    /** 调用结束后释放负载均衡器选择状态，主要服务于 least-connections 计数。 */
    public void release(String serviceName, String loadBalancerName, InetSocketAddress address) {
        resolveLoadBalancer(loadBalancerName).releaseSelection(serviceName, address);
    }

    /** 方法级负载均衡配置优先；未配置时使用客户端默认负载均衡器。 */
    private LoadBalancer resolveLoadBalancer(String loadBalancerName) {
        if (loadBalancerName == null || loadBalancerName.isBlank()) {
            return loadBalancer != null ? loadBalancer : LoadBalancerFactory.getDefaultLoadBalancer();
        }
        return LoadBalancerFactory.getLoadBalancer(loadBalancerName);
    }
}
