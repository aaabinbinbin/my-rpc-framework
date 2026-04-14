package com.rpc.core.invoke.filter.runtime;

import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.extension.spi.ExtensionLoader;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.invoke.filter.api.RpcFilter;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 过滤器管理器。
 *
 * 这个类负责在配置阶段把不同 phase 的过滤器列表解析出来并排序，
 * 运行时只需要按 phase 直接读取即可。
 */
public final class FilterManager {
    /**
     * 默认过滤器组合。
     * consumer / invoker / provider 三个阶段各自使用不同的默认链路。
     */
    private static final Map<FilterPhase, List<String>> DEFAULT_FILTER_NAMES = Map.of(
            FilterPhase.CONSUMER, List.of("trace", "mdc", "consumerMetrics"),
            FilterPhase.INVOKER, List.of("consumerCircuitBreaker"),
            FilterPhase.PROVIDER, List.of("providerRateLimit", "providerMdc", "providerMetrics")
    );
    /** 当前生效的过滤器链缓存，按 phase 分组。 */
    private static volatile Map<FilterPhase, List<RpcFilter>> filtersByPhase = buildFilters(new RpcFrameworkConfig());

    private FilterManager() {
    }

    /** 使用新的框架配置重新构建过滤器列表。 */
    public static void configure(RpcFrameworkConfig frameworkConfig) {
        filtersByPhase = buildFilters(frameworkConfig);
    }

    /** 获取某个阶段对应的过滤器链。 */
    public static List<RpcFilter> getFilters(FilterPhase phase) {
        return filtersByPhase.getOrDefault(phase, List.of());
    }

    /**
     * 按 phase 构建完整过滤器映射。
     *
     * 这一步在配置阶段一次完成，运行时直接复用结果，避免每次请求都重新做扩展解析和排序。
     */
    private static Map<FilterPhase, List<RpcFilter>> buildFilters(RpcFrameworkConfig frameworkConfig) {
        Map<FilterPhase, List<RpcFilter>> filters = new EnumMap<>(FilterPhase.class);
        filters.put(FilterPhase.CONSUMER, resolveFilters(
                FilterPhase.CONSUMER,
                frameworkConfig.getConsumerFilters(),
                frameworkConfig.getFilterOrders()
        ));
        filters.put(FilterPhase.INVOKER, resolveFilters(
                FilterPhase.INVOKER,
                frameworkConfig.getInvokerFilters(),
                frameworkConfig.getFilterOrders()
        ));
        filters.put(FilterPhase.PROVIDER, resolveFilters(
                FilterPhase.PROVIDER,
                frameworkConfig.getProviderFilters(),
                frameworkConfig.getFilterOrders()
        ));
        return filters;
    }

    /**
     * 解析某个阶段的过滤器列表。
     *
     * 顺序：
     * 1. 确定本阶段配置名列表。
     * 2. 通过 SPI 加载过滤器实现。
     * 3. 再次按 phase 过滤，防止错配到错误阶段。
     * 4. 最后按顺序排序。
     */
    private static List<RpcFilter> resolveFilters(FilterPhase phase,
                                                  List<String> configuredNames,
                                                  Map<String, Integer> orderOverrides) {
        List<String> names = (configuredNames == null || configuredNames.isEmpty())
                ? DEFAULT_FILTER_NAMES.getOrDefault(phase, List.of())
                : configuredNames;
        ExtensionLoader<RpcFilter> loader = ExtensionLoader.getExtensionLoader(RpcFilter.class);
        return names.stream()
                .map(loader::getExtension)
                .filter(filter -> filter.phase() == phase)
                .sorted(Comparator.comparingInt(filter -> orderOverrides.getOrDefault(resolveName(loader, filter), filter.order())))
                .collect(Collectors.toList());
    }

    /** 反向解析某个过滤器实现对应的扩展名，供顺序覆盖配置使用。 */
    private static String resolveName(ExtensionLoader<RpcFilter> loader, RpcFilter filter) {
        return loader.getSupportedExtensions().stream()
                .filter(name -> loader.getExtension(name).getClass() == filter.getClass())
                .findFirst()
                .orElse(filter.getClass().getSimpleName());
    }
}
