package com.rpc.core.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务实例快照测试。
 *
 * <p>测试目标：验证快照对象的不可变语义，避免服务发现回调或调用链持有同一个可变地址列表时
 * 意外修改全局服务目录。</p>
 */
@DisplayName("服务实例快照测试")
class ServiceInstancesSnapshotTest {

    @Test
    @DisplayName("构造快照时应复制传入地址列表")
    void shouldDefensivelyCopyAddresses() {
        List<InetSocketAddress> source = new ArrayList<>();
        source.add(new InetSocketAddress("127.0.0.1", 8080));

        ServiceInstancesSnapshot snapshot = ServiceInstancesSnapshot.of("userService", source);
        source.add(new InetSocketAddress("127.0.0.1", 8081));

        assertEquals(1, snapshot.getAddresses().size());
        assertEquals(8080, snapshot.getAddresses().get(0).getPort());
    }

    @Test
    @DisplayName("快照地址列表应不可修改")
    void addressesShouldBeUnmodifiable() {
        ServiceInstancesSnapshot snapshot = ServiceInstancesSnapshot.of(
                "userService",
                List.of(new InetSocketAddress("127.0.0.1", 8080))
        );

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getAddresses().add(new InetSocketAddress("127.0.0.1", 8081)));
    }

    @Test
    @DisplayName("空地址列表和 null 地址列表都应表示空快照")
    void shouldTreatNullAddressesAsEmptySnapshot() {
        assertTrue(ServiceInstancesSnapshot.of("userService", null).isEmpty());
        assertTrue(ServiceInstancesSnapshot.of("userService", List.of()).isEmpty());
    }
}
