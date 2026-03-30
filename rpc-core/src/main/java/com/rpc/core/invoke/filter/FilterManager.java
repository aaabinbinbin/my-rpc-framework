package com.rpc.core.invoke.filter;

import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.extension.spi.ExtensionLoader;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class FilterManager {
    // 未显式配置时的默认 filter 组合。
    // 这里按 phase 分组，是为了让 consumer / invoker / provider 三段职责清晰分离。
    private static final Map<FilterPhase, List<String>> DEFAULT_FILTER_NAMES = Map.of(
            FilterPhase.CONSUMER, List.of("trace", "mdc", "consumerMetrics"),
            FilterPhase.INVOKER, List.of("consumerCircuitBreaker"),
            FilterPhase.PROVIDER, List.of("providerRateLimit", "providerMdc", "providerMetrics")
    );
    private static volatile Map<FilterPhase, List<RpcFilter>> filtersByPhase = buildFilters(new RpcFrameworkConfig());

    private FilterManager() {
    }

    public static void configure(RpcFrameworkConfig frameworkConfig) {
        filtersByPhase = buildFilters(frameworkConfig);
    }

    public static List<RpcFilter> getFilters(FilterPhase phase) {
        return filtersByPhase.getOrDefault(phase, List.of());
    }

    private static Map<FilterPhase, List<RpcFilter>> buildFilters(RpcFrameworkConfig frameworkConfig) {
        // phase 维度的 filter 列表在配置阶段一次性构建好，运行时直接读取。
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

    private static List<RpcFilter> resolveFilters(FilterPhase phase,
                                                  List<String> configuredNames,
                                                  Map<String, Integer> orderOverrides) {
        List<String> names = (configuredNames == null || configuredNames.isEmpty())
                ? DEFAULT_FILTER_NAMES.getOrDefault(phase, List.of())
                : configuredNames;
        ExtensionLoader<RpcFilter> loader = ExtensionLoader.getExtensionLoader(RpcFilter.class);
        // 这里先按名字加载扩展，再按 phase 过滤、按顺序排序，
        // 保证一个 filter 即使被错误配置到别的 phase，也不会进入错误链路。
        return names.stream()
                .map(loader::getExtension)
                .filter(filter -> filter.phase() == phase)
                .sorted(Comparator.comparingInt(filter -> orderOverrides.getOrDefault(resolveName(loader, filter), filter.order())))
                .collect(Collectors.toList());
    }

    private static String resolveName(ExtensionLoader<RpcFilter> loader, RpcFilter filter) {
        return loader.getSupportedExtensions().stream()
                .filter(name -> loader.getExtension(name).getClass() == filter.getClass())
                .findFirst()
                .orElse(filter.getClass().getSimpleName());
    }
}

