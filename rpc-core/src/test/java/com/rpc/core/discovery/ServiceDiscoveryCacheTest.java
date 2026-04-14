package com.rpc.core.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 服务发现缓存测试。
 *
 * <p>测试目标：验证服务发现缓存只负责保存快照和更新时间，不主动判断 TTL，
 * 过期策略由上层 {@link ServiceDirectory} 控制。</p>
 */
@DisplayName("服务发现缓存测试")
class ServiceDiscoveryCacheTest {

    @Test
    @DisplayName("缓存写入后应能读取快照和更新时间")
    void shouldStoreSnapshotWithUpdateTime() {
        ServiceDiscoveryCache cache = new ServiceDiscoveryCache();
        ServiceInstancesSnapshot snapshot = ServiceInstancesSnapshot.of(
                "userService",
                List.of(new InetSocketAddress("127.0.0.1", 8080))
        );

        cache.put("userService", snapshot);
        ServiceDiscoveryCache.CacheEntry entry = cache.getEntry("userService");

        assertNotNull(entry);
        assertEquals(snapshot, cache.get("userService"));
        assertEquals(snapshot, entry.getSnapshot());
        assertNotNull(entry.getUpdatedAtMillis());
    }

    @Test
    @DisplayName("未命中缓存时应返回 null 以区分未缓存和空快照")
    void shouldReturnNullWhenServiceNotCached() {
        ServiceDiscoveryCache cache = new ServiceDiscoveryCache();

        assertNull(cache.get("missingService"));
        assertNull(cache.getEntry("missingService"));
    }

    @Test
    @DisplayName("清理缓存后不应保留旧快照")
    void shouldClearCachedSnapshots() {
        ServiceDiscoveryCache cache = new ServiceDiscoveryCache();
        cache.put("userService", ServiceInstancesSnapshot.of("userService", List.of()));

        cache.clear();

        assertNull(cache.get("userService"));
        assertNull(cache.getEntry("userService"));
    }
}
