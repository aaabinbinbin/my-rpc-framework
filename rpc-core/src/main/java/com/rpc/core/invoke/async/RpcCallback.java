package com.rpc.core.invoke.async;

/**
 * 异步 RPC（远程过程调用）调用的回调契约。
 */
@FunctionalInterface
public interface RpcCallback<T> {
    void onSuccess(T result);

    default void onFailure(Throwable cause) {
        cause.printStackTrace();
    }

    default void onComplete() {
    }
}
