package com.rpc.core.config;

final class RpcFilterConfigBinder {
    void bind(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        config.setConsumerFilters(propertySource.getList(RpcConfigKeys.FILTER_CONSUMER, config.getConsumerFilters()));
        config.setInvokerFilters(propertySource.getList(RpcConfigKeys.FILTER_INVOKER, config.getInvokerFilters()));
        config.setProviderFilters(propertySource.getList(RpcConfigKeys.FILTER_PROVIDER, config.getProviderFilters()));
        config.setFilterOrders(propertySource.getIntegerMapByPrefix(RpcConfigKeys.FILTER_ORDER_PREFIX));
    }
}
