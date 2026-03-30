package com.rpc.core.transport;

import com.rpc.core.registry.LocalRegistry;

public interface RpcServer extends AutoCloseable {
    void start() throws Exception;

    void shutdown();

    LocalRegistry getLocalRegistry();

    @Override
    default void close() {
        shutdown();
    }
}

