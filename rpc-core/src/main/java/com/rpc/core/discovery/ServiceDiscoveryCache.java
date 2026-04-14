package com.rpc.core.discovery;

import lombok.Getter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务发现结果的本地缓存。
 *
 * 所处阶段：consumer 调用链完成服务名解析后、负载均衡选址前。
 * 主要职责：按服务名保存最近一次可用的 provider 地址快照，为注册中心短暂不可用时的
 * stale fallback 提供数据来源。
 *
 * 注意事项：
 * 1. 该类只负责缓存快照和更新时间，不主动判断 TTL 是否过期，过期策略由 ServiceDirectory 控制。
 * 2. 使用 ConcurrentHashMap 保证服务发现监听回调和业务调用线程可以并发读写。
 * 3. 快照对象必须保持不可变，避免调用方拿到缓存后修改全局状态。
 */
public class ServiceDiscoveryCache {
    /** 按 serviceName 保存的最新地址快照，value 中同时记录更新时间。 */
    private final ConcurrentHashMap<String, CacheEntry> snapshots = new ConcurrentHashMap<>();

    /**
     * 获取缓存条目，调用方可同时读取快照内容和更新时间。
     *
     * 边界处理：服务从未缓存过时返回 null，由上层决定是否访问注册中心或直接失败。
     */
    public CacheEntry getEntry(String serviceName) {
        return snapshots.get(serviceName);
    }

    /**
     * 获取服务地址快照。
     *
     * 适用场景：调用方只关心地址列表，不关心更新时间。
     * 边界处理：服务不存在时返回 null，而不是空快照，用于区分“未缓存”和“缓存为空列表”。
     */
    public ServiceInstancesSnapshot get(String serviceName) {
        CacheEntry entry = snapshots.get(serviceName);
        return entry == null ? null : entry.getSnapshot();
    }

    /**
     * 写入最新地址快照并记录当前 JVM 时间。
     *
     * 注意事项：这里不校验 snapshot 是否为空，空实例列表也是一种有效状态，表示服务当前无可用 provider。
     */
    public ServiceInstancesSnapshot put(String serviceName, ServiceInstancesSnapshot snapshot) {
        snapshots.put(serviceName, new CacheEntry(snapshot, System.currentTimeMillis()));
        return snapshot;
    }

    /**
     * 暴露全部缓存条目，用于批量清理、观测或测试断言。
     *
     * 注意事项：返回的是 ConcurrentHashMap values 视图，遍历期间允许并发修改。
     */
    public Iterable<CacheEntry> entries() {
        return snapshots.values();
    }

    /**
     * 清空本地缓存。
     *
     * 适用场景：客户端关闭、测试重置或注册中心策略重新初始化。
     */
    public void clear() {
        snapshots.clear();
    }

    /**
     * 单个服务的缓存条目。
     *
     * 主要职责：把不可变地址快照和更新时间绑定在一起，方便 ServiceDirectory 判断是否允许使用过期数据。
     */
    @Getter
    public static final class CacheEntry {
        /** 最近一次从注册中心或监听回调得到的服务实例快照。 */
        private final ServiceInstancesSnapshot snapshot;
        /** 写入缓存时的 JVM 本地毫秒时间，用于 TTL 判断。 */
        private final long updatedAtMillis;

        /**
         * 创建缓存条目。
         *
         * 注意事项：构造器私有，统一通过外层 put 方法写入，避免外部绕过更新时间语义。
         */
        private CacheEntry(ServiceInstancesSnapshot snapshot, long updatedAtMillis) {
            this.snapshot = snapshot;
            this.updatedAtMillis = updatedAtMillis;
        }
    }
}

