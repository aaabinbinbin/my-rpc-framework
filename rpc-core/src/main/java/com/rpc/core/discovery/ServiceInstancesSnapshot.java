package com.rpc.core.discovery;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 某个服务在某一时刻的 provider 地址快照。
 *
 * 所处阶段：服务发现层输出到负载均衡层之前。
 * 主要职责：把 serviceName 和一组 InetSocketAddress 绑定为不可变对象，避免调用链中被意外修改。
 *
 * 设计原因：注册中心监听回调、服务目录缓存、业务调用线程可能并发访问同一份地址列表，
 * 使用不可变快照可以降低同步复杂度。
 */
@Getter
@EqualsAndHashCode
public final class ServiceInstancesSnapshot {
    /** 服务接口全限定名或框架内部约定的服务名。 */
    private final String serviceName;
    /** 当前快照中的 provider 地址列表，构造后不可变。 */
    private final List<InetSocketAddress> addresses;

    /**
     * 创建不可变快照。
     *
     * 边界处理：构造时复制 addresses，防止调用方继续持有原 List 并修改快照内容。
     */
    private ServiceInstancesSnapshot(String serviceName, List<InetSocketAddress> addresses) {
        this.serviceName = serviceName;
        this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
    }

    /**
     * 构建服务实例快照。
     *
     * 边界处理：addresses 为 null 时转为空列表，避免服务发现异常路径出现 NPE。
     */
    public static ServiceInstancesSnapshot of(String serviceName, List<InetSocketAddress> addresses) {
        return new ServiceInstancesSnapshot(serviceName, addresses == null ? List.of() : addresses);
    }

    /**
     * 判断当前服务是否没有可用 provider。
     *
     * 适用场景：负载均衡前快速失败，或注册中心推送空列表时清理连接池。
     */
    public boolean isEmpty() {
        return addresses.isEmpty();
    }
}

