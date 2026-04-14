package com.rpc.core.registry;

import com.rpc.core.registry.local.LocalRegistryImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：本地注册中心实现测试")
class LocalRegistryImplTest {
    @DisplayName("验证保持注册数据Isolated按注册中心实例场景")
    @Test
    void shouldKeepRegistrationsIsolatedPerRegistryInstance() {
        LocalRegistryImpl first = new LocalRegistryImpl(null, "127.0.0.1", 8080);
        LocalRegistryImpl second = new LocalRegistryImpl(null, "127.0.0.1", 8081);
        Object firstService = new Object();
        Object secondService = new Object();

        first.register("svc-a", firstService);
        second.register("svc-b", secondService);

        assertSame(firstService, first.getService("svc-a"));
        assertSame(secondService, second.getService("svc-b"));
        assertFalse(first.contains("svc-b"));
        assertFalse(second.contains("svc-a"));
        assertThrows(RuntimeException.class, () -> first.getService("svc-b"));
        assertThrows(RuntimeException.class, () -> second.getService("svc-a"));
    }

    @DisplayName("验证仅Unregister来自Current注册中心实例场景")
    @Test
    void shouldOnlyUnregisterFromCurrentRegistryInstance() {
        LocalRegistryImpl first = new LocalRegistryImpl(null, "127.0.0.1", 8080);
        LocalRegistryImpl second = new LocalRegistryImpl(null, "127.0.0.1", 8081);

        first.register("svc-a", new Object());
        second.register("svc-a", new Object());

        first.unregister("svc-a");

        assertFalse(first.contains("svc-a"));
        assertTrue(second.contains("svc-a"));
    }
}
