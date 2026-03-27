package com.rpc.transport;

import com.rpc.registry.LocalRegistry;

public interface RpcServer extends AutoCloseable {
    void start() throws Exception;

    void shutdown();

    LocalRegistry getLocalRegistry();

    @Override
    default void close() {
        shutdown();
    }
}
