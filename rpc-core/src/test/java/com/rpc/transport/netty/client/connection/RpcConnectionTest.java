package com.rpc.transport.netty.client.connection;

import com.rpc.core.transport.netty.client.connection.RpcConnection;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC连接测试")
class RpcConnectionTest {
    @DisplayName("验证限制在途请求按连接场景")
    @Test
    void shouldLimitInflightRequestsPerConnection() {
        RpcConnection connection = new RpcConnection(new EmbeddedChannel(), "127.0.0.1", 8080, 1);

        assertTrue(connection.tryAcquireRequestSlot());
        assertEquals(1, connection.getInflightRequests().get());
        assertFalse(connection.tryAcquireRequestSlot());

        connection.releaseRequestSlot();

        assertEquals(0, connection.getInflightRequests().get());
        assertTrue(connection.tryAcquireRequestSlot());
    }
}
