package com.rpc.async;

/**
 * RPC 异步调用回调接口
 * 当响应到达时自动触发
 *
 * @param <T> 结果类型
 */
@FunctionalInterface
public interface RpcCallback<T> {
    /**
     * 成功回调
     * @param result 调用结果
     */
    void onSuccess(T result);

    /**
     * 失败回调（默认实现，可选）
     * @param cause 异常原因
     */
    default void onFailure(Throwable cause) {
        // 默认不做处理，子类可以重写
        cause.printStackTrace();
    }

    /**
     * 完成后回调（无论成功失败，可选）
     */
    default void onComplete() {
        // 可选的清理逻辑
    }
}
