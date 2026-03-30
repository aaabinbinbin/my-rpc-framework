package com.rpc.core.invoke.async;

import com.rpc.core.protocol.RpcResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Getter
public class AsyncRpcResult<T> {
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final CompletableFuture<RpcResponse> responseFuture;
    private final Class<T> resultType;

    public AsyncRpcResult(Class<T> resultType) {
        this(new CompletableFuture<>(), resultType);
    }

    public AsyncRpcResult(CompletableFuture<RpcResponse> responseFuture, Class<T> resultType) {
        this.responseFuture = responseFuture;
        this.resultType = resultType;
    }

    public void setResult(RpcResponse response) {
        responseFuture.complete(response);
    }

    public void setException(Throwable cause) {
        responseFuture.completeExceptionally(cause);
    }

    public T get() throws Exception {
        return get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    public T get(long timeout, TimeUnit unit) throws Exception {
        RpcResponse response = responseFuture.get(timeout, unit);
        if (response.getCode() != 200) {
            throw new RuntimeException("RPC invoke failed: " + response.getMessage());
        }
        Object data = response.getData();
        if (data == null) {
            return null;
        }
        if (resultType.isInstance(data)) {
            return (T) data;
        }
        log.warn("Result type mismatch, expected={}, actual={}",
                resultType.getName(), data.getClass().getName());
        return (T) data;
    }

    public void addCallback(RpcCallback<T> callback) {
        responseFuture.whenComplete((response, throwable) -> {
            try {
                if (throwable != null) {
                    callback.onFailure(throwable);
                    return;
                }
                try {
                    callback.onSuccess(get(0, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    callback.onFailure(e);
                }
            } finally {
                callback.onComplete();
            }
        });
    }

    public CompletableFuture<T> toCompletableFuture() {
        return responseFuture.thenApply(response -> {
            try {
                return get(0, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public boolean isDone() {
        return responseFuture.isDone();
    }

    public boolean isCancelled() {
        return responseFuture.isCancelled();
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        return responseFuture.cancel(mayInterruptIfRunning);
    }
}
