package com.rpc.core.invoke.filter;

import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FilterManagerConfigTest {
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

    @Test
    void shouldNotResetRuntimeDegradationStateWhenReconfiguringFilters() {
        RpcFrameworkConfig runtimeConfig = new RpcFrameworkConfig();
        runtimeConfig.setEnableDegradation(true);
        runtimeConfig.setDegradationFailureThreshold(1);
        FilterRuntimeConfigurator.configureConsumer(
                runtimeConfig,
                DegradationPolicyFactory.create("defaultValue", Map.of("svc#m", "fallback"))
        );

        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setInvokerFilters(List.of("consumerCircuitBreaker"));
        FilterManager.configure(frameworkConfig);

        assertNotNull(FilterRuntimeConfig.getConsumerDegradationPolicy());
        assertEquals(1, FilterRuntimeConfig.getConsumerFailureThreshold());

        FilterRuntimeConfigurator.configureConsumer(new RpcFrameworkConfig(), null);
    }
}

