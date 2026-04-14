package com.rpc.core.config.filter;

import com.rpc.core.config.framework.RpcConfigKeys;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.config.source.RpcPropertySource;

/**
 * 过滤器链配置绑定器。
 *
 * 所处阶段：框架启动加载配置时，FilterManager 初始化默认或自定义过滤器链之前。
 * 主要职责：绑定 consumer、invoker、provider 三个阶段的过滤器名称列表，以及过滤器顺序覆盖配置。
 *
 * 注意事项：这里只解析配置，不加载 SPI 实例；真正的扩展加载和 phase 校验在 FilterManager 中完成。
 */
public final class RpcFilterConfigBinder {
    /**
     * 将过滤器相关配置写入框架配置对象。
     *
     * 边界处理：列表为空时保留默认过滤器链；order 前缀下的动态 key 会被解析为 name -> order 映射。
     */
    public void bind(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        config.setConsumerFilters(propertySource.getList(RpcConfigKeys.FILTER_CONSUMER, config.getConsumerFilters()));
        config.setInvokerFilters(propertySource.getList(RpcConfigKeys.FILTER_INVOKER, config.getInvokerFilters()));
        config.setProviderFilters(propertySource.getList(RpcConfigKeys.FILTER_PROVIDER, config.getProviderFilters()));
        config.setFilterOrders(propertySource.getIntegerMapByPrefix(RpcConfigKeys.FILTER_ORDER_PREFIX));
    }
}
