package com.rpc.async;

import com.rpc.protocol.RpcResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 异步 RPC 结果封装
 * 包装 CompletableFuture，提供更友好的 API
 *
 * @param <T> 期望的结果类型
 */
@Slf4j
@Getter
public class AsyncRpcResult<T> {
    /** 内部使用的 CompletableFuture */
    private final CompletableFuture<RpcResponse> responseFuture;

    /** 期望的返回类型 */
    private final Class<T> resultType;

    /** 默认的超时时间（秒） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    public AsyncRpcResult(Class<T> resultType) {
        this.resultType = resultType;
        this.responseFuture = new CompletableFuture<>();
    }

    public AsyncRpcResult(CompletableFuture<RpcResponse> responseFuture, Class<T> resultType) {
        this.responseFuture = responseFuture;
        this.resultType = resultType;
    }

    /**
     * 设置结果（成功）
     */
    public void setResult(RpcResponse response) {
        responseFuture.complete(response);
    }

    /**
     * 设置异常（失败）
     */
    public void setException(Throwable cause) {
        responseFuture.completeExceptionally(cause);
    }

    /**
     * 获取结果（阻塞，带默认超时）
     */
    public T get() throws Exception {
        return get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 获取结果（阻塞，带超时）
     */
    @SuppressWarnings("unchecked")
    public T get(long timeout, TimeUnit unit) throws Exception {
        RpcResponse response = responseFuture.get(timeout, unit);

        // 检查响应状态
        if (response.getCode() != 200) {
            throw new RuntimeException("RPC 调用失败：" + response.getMessage());
        }

        // 类型转换
        Object data = response.getData();
        if (data == null) {
            return null;
        }

        if (resultType.isInstance(data)) {
            return (T) data;
        } else {
            // 如果类型不匹配，尝试转换（可能需要序列化框架支持）
            log.warn("返回数据类型不匹配：期望={}, 实际={}",
                    resultType.getName(), data.getClass().getName());
            return (T) data;
        }
    }

    /**
     * 注册回调（非阻塞）
     */
    public void addCallback(RpcCallback<T> callback) {
        responseFuture.whenComplete((response, throwable) -> {
            try {
                if (throwable != null) {
                    callback.onFailure(throwable);
                } else {
                    T result = null;
                    try {
                        result = get(0, TimeUnit.MILLISECONDS); // 尝试立即获取
                    } catch (Exception e) {
                        callback.onFailure(e);
                        return;
                    }
                    callback.onSuccess(result);
                }
            } finally {
                callback.onComplete();
            }
        });
    }

    /**
     * 转换为 CompletableFuture<T>（方便链式调用）
     */
    public CompletableFuture<T> toCompletableFuture() {
        return responseFuture.thenApply(response -> {
            try {
                return get(0, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 判断是否完成
     */
    public boolean isDone() {
        return responseFuture.isDone();
    }

    /**
     * 判断是否取消
     */
    public boolean isCancelled() {
        return responseFuture.isCancelled();
    }

    /**
     * 取消（尝试中断）
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        return responseFuture.cancel(mayInterruptIfRunning);
    }
}
