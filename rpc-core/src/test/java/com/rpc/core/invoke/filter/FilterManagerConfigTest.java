package com.rpc.core.invoke.filter;

import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.invoke.filter.api.RpcFilter;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfig;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfigurator;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：过滤器管理器配置测试")
class FilterManagerConfigTest {
    @DisplayName("验证使用已配置过滤器名称按阶段场景")
    @Test
    void shouldUseConfiguredFilterNamesPerPhase() {
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setConsumerFilters(List.of("trace"));
        frameworkConfig.setInvokerFilters(List.of("consumerCircuitBreaker"));
        frameworkConfig.setProviderFilters(List.of("providerMdc"));

        FilterManager.configure(frameworkConfig);

        assertEquals(1, FilterManager.getFilters(FilterPhase.CONSUMER).size());
        assertEquals("TraceFilter", FilterManager.getFilters(FilterPhase.CONSUMER).get(0).getClass().getSimpleName());
        assertEquals(1, FilterManager.getFilters(FilterPhase.INVOKER).size());
        assertEquals("ConsumerCircuitBreakerFilter", FilterManager.getFilters(FilterPhase.INVOKER).get(0).getClass().getSimpleName());
        assertEquals(1, FilterManager.getFilters(FilterPhase.PROVIDER).size());
        assertEquals("ProviderMdcFilter", FilterManager.getFilters(FilterPhase.PROVIDER).get(0).getClass().getSimpleName());
    }

    @DisplayName("验证应用已配置过滤器顺序覆盖场景")
    @Test
    void shouldApplyConfiguredFilterOrderOverrides() {
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setProviderFilters(List.of("providerMetrics", "providerMdc"));
        frameworkConfig.setFilterOrders(Map.of(
                "providerMetrics", 1,
                "providerMdc", 20
        ));

        FilterManager.configure(frameworkConfig);

        List<RpcFilter> providerFilters = FilterManager.getFilters(FilterPhase.PROVIDER);
        assertEquals(2, providerFilters.size());
        assertEquals("ProviderMetricsFilter", providerFilters.get(0).getClass().getSimpleName());
        assertEquals("ProviderMdcFilter", providerFilters.get(1).getClass().getSimpleName());
    }

    @DisplayName("验证不重置运行时降级状态当重新配置Filters场景")
    @Test
    void shouldNotResetRuntimeDegradationStateWhenReconfiguringFilters() {
        RpcFrameworkConfig runtimeConfig = new RpcFrameworkConfig();
        runtimeConfig.setEnableDegradation(true);
        FilterRuntimeConfigurator.configureConsumer(
                runtimeConfig,
                DegradationPolicyFactory.create("defaultValue", Map.of("svc#m", "fallback"))
        );

        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setInvokerFilters(List.of("consumerCircuitBreaker"));
        FilterManager.configure(frameworkConfig);

        assertNotNull(FilterRuntimeConfig.getConsumerDegradationPolicy());

        FilterRuntimeConfigurator.configureConsumer(new RpcFrameworkConfig(), null);
    }
}

