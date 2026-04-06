package com.rpc.core.transport;

import com.rpc.core.registry.LocalRegistry;

/**
 * RPC 服务端统一抽象。
 *
 * 无论底层是 Netty 还是 Socket，
 * 对 provider bootstrap 来说都只关心三件事：
 * 1. 启动服务端。
 * 2. 关闭服务端。
 * 3. 拿到本地注册表注册服务对象。
 */
public interface RpcServer extends AutoCloseable {
    /** 启动服务端并开始监听端口。 */
    void start() throws Exception;

    /** 关闭服务端。 */
    void shutdown();

    /** 获取 provider 本地注册表。 */
    LocalRegistry getLocalRegistry();

    @Override
    default void close() {
        shutdown();
    }
}
