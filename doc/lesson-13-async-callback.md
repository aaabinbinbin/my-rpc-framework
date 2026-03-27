# 第 13 课：异步调用与回调 - 实现非阻塞 RPC 调用

## 课程目标

通过本节课，你将掌握如何在 RPC 框架中实现异步调用与回调机制，使客户端能够：

- ✅ **同步调用**：阻塞等待结果（传统方式）
- ✅ **异步调用**：立即返回，通过 Future 获取结果
- ✅ **回调通知**：结果到达时自动触发回调函数
- ✅ **One-Way 调用**：只发送请求，不关心结果
- ✅ **组合多个调用**：并行发起多个请求并合并结果

**核心设计理念**：
- 🎯 **低耦合**：业务代码无需关心网络细节
- 🎯 **高内聚**：异步相关功能集中在独立模块
- 🎯 **易用性**：Client 和 Server 端都只需几行代码即可使用

---

## 一、知识点讲解

### 1.1 为什么需要异步调用？

#### 同步 vs 异步对比

**同步调用（Synchronous）**：
```java
// 线程阻塞，直到收到响应或超时
User user = helloService.getUserById(1L);
System.out.println("获取到用户：" + user);
// 线程一直等待，浪费资源
```

**异步调用（Asynchronous）**：
```java
// 立即返回 Future，不阻塞当前线程
CompletableFuture<User> future = helloService.getUserByIdAsync(1L);

// 可以做其他事情
doOtherWork();

// 需要时再获取结果（可选阻塞）
User user = future.get();
System.out.println("获取到用户：" + user);
```

**优势**：
- 📈 **提高吞吐量**：单线程可以并发多个请求
- ⚡ **降低延迟**：不需要等待慢请求
- 💪 **更好的用户体验**：UI 线程不会卡死
- 🔥 **资源利用率高**：避免线程空转等待

---

### 1.2 CompletableFuture 详解

#### 什么是 CompletableFuture？

`CompletableFuture` 是 Java 8 提供的异步编程工具，它：
- 实现了 `Future` 接口（可以获取结果）
- 实现了 `CompletionStage` 接口（支持链式回调）
- 可以手动完成（`complete()`）
- 支持函数式组合

#### 核心方法

```java
// 1. 创建未完成的 Future
CompletableFuture<T> future = new CompletableFuture<>();

// 2. 完成 Future（设置结果）
future.complete(result);  // 正常完成
future.completeExceptionally(e);  // 异常完成

// 3. 获取结果（阻塞）
T result = future.get();  // 阻塞等待
T result = future.get(timeout, TimeUnit.SECONDS);  // 带超时

// 4. 注册回调（非阻塞）
future.thenAccept(result -> {
    // 处理结果
});

future.exceptionally(ex -> {
    // 处理异常
    return null;
});

// 5. 链式调用
future.thenApply(result -> transform(result))
      .thenAccept(this::process)
      .exceptionally(ex -> handleError(ex));
```

#### 实战示例

```java
// 模拟异步 RPC 调用
public CompletableFuture<User> getUserAsync(Long id) {
    CompletableFuture<User> future = new CompletableFuture<>();
    
    // 在另一个线程执行实际调用
    CompletableFuture.supplyAsync(() -> {
        // 网络调用
        return rpcClient.sendRequest(createGetUserRequest(id));
    }).thenApply(response -> {
        // 解析响应
        return (User) response.getData();
    }).whenComplete((result, ex) -> {
        // 无论成功失败都会执行
        if (ex != null) {
            future.completeExceptionally(ex);
        } else {
            future.complete(result);
        }
    });
    
    return future;
}
```

---

### 1.3 回调函数（Callback）

#### 什么是回调？

回调是一个在特定事件发生时被自动调用的函数。在 RPC 中，当响应到达时，自动触发回调函数通知调用方。

#### 回调接口设计

```java
// 定义通用回调接口
public interface RpcCallback<T> {
    /**
     * 成功回调
     */
    void onSuccess(T result);
    
    /**
     * 失败回调
     */
    void onFailure(Throwable cause);
}
```

#### 使用示例

```java
// 带回调的异步调用
helloService.getUserByIdWithCallback(1L, new RpcCallback<User>() {
    @Override
    public void onSuccess(User user) {
        System.out.println("收到用户：" + user);
    }
    
    @Override
    public void onFailure(Throwable cause) {
        System.err.println("调用失败：" + cause.getMessage());
    }
});
```

---

### 1.4 One-Way 调用模式

#### 什么是 One-Way 调用？

One-Way（单向）调用是指客户端只发送请求，不等待响应。适用于：
- ✅ 日志记录
- ✅ 配置更新
- ✅ 通知类操作
- ✅ 对结果不敏感的场景

#### 特点

| 特性 | 普通调用 | One-Way 调用 |
|------|---------|-------------|
| 是否需要响应 | ✅ 是 | ❌ 否 |
| 是否阻塞 | ✅ 是 | ❌ 否 |
| 性能 | 一般 | 更高 |
| 可靠性 | 高（可重试） | 低（发了就不管） |

---

### 1.5 并行调用与结果合并

#### 场景

假设你需要同时调用 3 个服务获取数据，然后合并结果：

```java
// 串行调用（总耗时 = t1 + t2 + t3）
User user = userService.getUser(id);
Order order = orderService.getOrder(id);
Product product = productService.getProduct(id);

// 并行调用（总耗时 = max(t1, t2, t3)）
CompletableFuture<User> userFuture = userService.getUserAsync(id);
CompletableFuture<Order> orderFuture = orderService.getOrderAsync(id);
CompletableFuture<Product> productFuture = productService.getProductAsync(id);

// 等待所有完成
CompletableFuture.allOf(userFuture, orderFuture, productFuture).join();

// 获取所有结果
User user = userFuture.get();
Order order = orderFuture.get();
Product product = productFuture.get();
```

---

## 二、模块设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────┐
│              异步调用模块 (async)                     │
├─────────────────────────────────────────────────────┤
│  RpcCallback<T>         - 回调接口                   │
│  AsyncRpcResult<T>      - 异步结果封装               │
│  AsyncInvocationHandler - 异步调用处理器             │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│           现有 RequestManager（增强版）                │
│  - 管理 CompletableFuture                           │
│  - 支持超时清理                                      │
│  - 支持回调注册                                      │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│          代理层（透明支持同步/异步）                    │
│  - 根据返回值类型自动选择模式                         │
│  - CompletableFuture<T> → 异步                       │
│  - T → 同步                                          │
└─────────────────────────────────────────────────────┘
```

### 2.2 设计原则

1. **对业务透明**：业务代码无需关心底层是同步还是异步
2. **零侵入**：不影响现有的同步调用
3. **易扩展**：轻松添加新的回调、超时等功能
4. **高性能**：无锁设计，减少上下文切换

---

## 三、代码实现

### 3.1 定义回调接口

**文件路径**：`rpc-core/src/main/java/com/rpc/async/RpcCallback.java`

```java
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
```

**说明**：
- 使用泛型支持任意返回类型
- `default` 方法提供灵活的回调选项
- `@FunctionalInterface` 支持 Lambda 表达式

---

### 3.2 封装异步结果

**文件路径**：`rpc-core/src/main/java/com/rpc/async/AsyncRpcResult.java`

```java
package com.rpc.async;

import com.rpc.protocol.RpcResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
```

**说明**：
- 包装了底层的 `CompletableFuture<RpcResponse>`
- 提供类型安全的 `get()` 方法
- 支持多种获取结果的方式（阻塞/非阻塞）
- 内置超时处理

---

### 3.3 增强 RequestManager

**文件路径**：`rpc-core/src/main/java/com/rpc/transport/netty/client/manager/RequestManager.java`

```java
package com.rpc.transport.netty.client.manager;

import com.rpc.async.AsyncRpcResult;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 请求管理器（增强版）
 * 管理所有发出但未收到响应的请求
 * 
 * 新增功能：
 * - 支持超时自动清理
 * - 支持 AsyncRpcResult
 * - 定时任务清理超时请求
 */
@Slf4j
public class RequestManager {
    /**
     * 存储待处理的请求
     * key: requestId
     * value: CompletableFuture（用于接收响应）
     */
    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests = new ConcurrentHashMap<>();
    
    /**
     * 存储带超时的请求（用于清理）
     * key: requestId
     * value: 过期时间戳
     */
    private final Map<Long, Long> requestTimeouts = new ConcurrentHashMap<>();
    
    /** 定时任务执行器（用于清理超时请求） */
    private static final ScheduledExecutorService SCHEDULER = 
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "RequestManager-Timeout-Cleaner");
                t.setDaemon(true);
                return t;
            });
    
    /** 默认超时时间（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 30000; // 30 秒
    
    // 启动定时清理任务
    static {
        SCHEDULER.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            requestTimeouts.entrySet().removeIf(entry -> {
                if (now > entry.getValue()) {
                    Long requestId = entry.getKey();
                    CompletableFuture<RpcResponse> future = 
                            pendingRequests.remove(requestId);
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(
                                new java.util.concurrent.TimeoutException(
                                        "请求超时：requestId=" + requestId));
                        log.warn("清理超时请求：requestId={}", requestId);
                    }
                    return true; // 移除
                }
                return false; // 保留
            });
        }, 10, 5, TimeUnit.SECONDS); // 每 5 秒清理一次
    }

    /**
     * 添加新的请求（默认超时）
     * @param requestId 请求 ID
     * @return CompletableFuture
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        return addRequest(requestId, DEFAULT_TIMEOUT_MS);
    }
    
    /**
     * 添加新的请求（自定义超时）
     * @param requestId 请求 ID
     * @param timeoutMs 超时时间（毫秒）
     * @return CompletableFuture
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId, long timeoutMs) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        requestTimeouts.put(requestId, System.currentTimeMillis() + timeoutMs);
        log.debug("添加请求：requestId={}, timeout={}ms", requestId, timeoutMs);
        return future;
    }
    
    /**
     * 添加异步结果请求
     * @param requestId 请求 ID
     * @param asyncResult 异步结果封装
     * @return AsyncRpcResult
     */
    public <T> AsyncRpcResult<T> addAsyncRequest(long requestId, AsyncRpcResult<T> asyncResult) {
        pendingRequests.put(requestId, asyncResult.getResponseFuture());
        requestTimeouts.put(requestId, System.currentTimeMillis() + DEFAULT_TIMEOUT_MS);
        log.debug("添加异步请求：requestId={}", requestId);
        return asyncResult;
    }
    
    /**
     * 添加异步结果请求（自定义超时）
     */
    public <T> AsyncRpcResult<T> addAsyncRequest(long requestId, AsyncRpcResult<T> asyncResult, 
                                                   long timeoutMs) {
        pendingRequests.put(requestId, asyncResult.getResponseFuture());
        requestTimeouts.put(requestId, System.currentTimeMillis() + timeoutMs);
        log.debug("添加异步请求：requestId={}, timeout={}ms", requestId, timeoutMs);
        return asyncResult;
    }

    /**
     * 收到响应，完成 Future
     * @param response RPC 响应
     */
    public void completeResponse(RpcResponse response) {
        long requestId = Long.parseLong(response.getRequestId());
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        requestTimeouts.remove(requestId);
        
        if (future != null) {
            future.complete(response);
            log.debug("完成请求：requestId={}, code={}", requestId, response.getCode());
        } else {
            log.warn("未找到对应的请求：requestId={}", requestId);
        }
    }

    /**
     * 请求失败，异常完成 Future
     * @param requestId 请求 ID
     * @param cause 异常原因
     */
    public void failRequest(long requestId, Throwable cause) {
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        requestTimeouts.remove(requestId);
        
        if (future != null) {
            future.completeExceptionally(cause);
            log.error("请求失败：requestId={}", requestId, cause);
        }
    }
    
    /**
     * 清理超时的请求（手动调用，作为定时的补充）
     * 注：主要由定时任务自动执行，此方法供特殊场景使用
     */
    public void clearTimeoutRequests() {
        long now = System.currentTimeMillis();
        requestTimeouts.entrySet().removeIf(entry -> {
            if (now > entry.getValue()) {
                Long requestId = entry.getKey();
                failRequest(requestId, new java.util.concurrent.TimeoutException(
                        "请求超时：" + requestId));
                return true;
            }
            return false;
        });
    }

    /**
     * 获取待处理请求数量
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }
    
    /**
     * 关闭管理器（清理所有请求）
     */
    public void shutdown() {
        log.info("关闭请求管理器，清理{}个待处理请求", pendingRequests.size());
        
        // 取消所有待处理请求
        pendingRequests.forEach((requestId, future) -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        
        pendingRequests.clear();
        requestTimeouts.clear();
        
        // 关闭定时任务
        SCHEDULER.shutdown();
        try {
            if (!SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

**改进点**：
- ✅ 增加了超时管理机制
- ✅ 支持 `AsyncRpcResult` 封装
- ✅ 后台定时任务自动清理超时请求
- ✅ 优雅关闭支持

---

### 3.4 实现异步调用处理器

**文件路径**：`rpc-core/src/main/java/com/rpc/async/AsyncInvocationHandler.java`

```java
package com.rpc.async;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.transport.netty.client.manager.RequestManager;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * 异步调用处理器
 * 根据方法返回值类型自动选择同步/异步模式
 * 
 * 支持的返回类型：
 * - CompletableFuture<T> → 异步调用
 * - AsyncRpcResult<T> → 异步调用（封装版）
 * - 其他 → 同步调用（阻塞等待）
 */
@Slf4j
public class AsyncInvocationHandler implements InvocationHandler {
    private final Class<?> serviceClass;
    private final RpcNettyClient rpcClient;
    private final RequestManager requestManager;

    public AsyncInvocationHandler(Class<?> serviceClass, 
                                   RpcNettyClient rpcClient,
                                   RequestManager requestManager) {
        this.serviceClass = serviceClass;
        this.rpcClient = rpcClient;
        this.requestManager = requestManager;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 跳过 Object 类的方法
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }
        
        // 2. 构建 RPC 请求
        RpcRequest request = buildRpcRequest(method, args);
        
        // 3. 判断返回类型，决定调用模式
        Class<?> returnType = method.getReturnType();
        
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            // CompletableFuture<T> → 异步调用
            log.debug("异步调用：{}.{}", request.getServiceName(), request.getMethodName());
            return invokeAsync(request, extractGenericType(returnType));
            
        } else if (AsyncRpcResult.class.isAssignableFrom(returnType)) {
            // AsyncRpcResult<T> → 异步调用（封装版）
            log.debug("异步调用（封装版）：{}.{}", 
                    request.getServiceName(), request.getMethodName());
            return invokeAsyncResult(request, extractGenericType(returnType));
            
        } else {
            // 其他类型 → 同步调用（阻塞）
            log.debug("同步调用：{}.{}", request.getServiceName(), request.getMethodName());
            return invokeSync(request);
        }
    }
    
    /**
     * 构建 RPC 请求对象
     */
    private RpcRequest buildRpcRequest(Method method, Object[] args) {
        RpcRequest request = new RpcRequest();
        request.setServiceName(serviceClass.getName());
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setParameters(args);
        request.setReturnType(method.getReturnType());
        return request;
    }
    
    /**
     * 同步调用（阻塞等待结果）
     */
    private Object invokeSync(RpcRequest request) throws Exception {
        // 调用现有的同步方法
        return rpcClient.sendRequest(request);
    }
    
    /**
     * 异步调用 - 返回 CompletableFuture<T>
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> invokeAsync(RpcRequest request, Class<?> resultType) 
            throws Exception {
        // 生成请求 ID
        long requestId = generateRequestId();
        request.setRequestId(String.valueOf(requestId));
        
        // 创建 AsyncRpcResult
        @SuppressWarnings("rawtypes")
        AsyncRpcResult asyncResult = new AsyncRpcResult(resultType);
        
        // 注册到 RequestManager
        requestManager.addAsyncRequest(requestId, asyncResult);
        
        // 发送请求（非阻塞）
        rpcClient.sendRequestAsync(request, requestId);
        
        // 转换为 CompletableFuture 返回
        return (CompletableFuture<Object>) asyncResult.toCompletableFuture();
    }
    
    /**
     * 异步调用 - 返回 AsyncRpcResult<T>
     */
    @SuppressWarnings("unchecked")
    private AsyncRpcResult<Object> invokeAsyncResult(RpcRequest request, Class<?> resultType) 
            throws Exception {
        // 生成请求 ID
        long requestId = generateRequestId();
        request.setRequestId(String.valueOf(requestId));
        
        // 创建 AsyncRpcResult
        @SuppressWarnings("rawtypes")
        AsyncRpcResult asyncResult = new AsyncRpcResult(resultType);
        
        // 注册到 RequestManager
        requestManager.addAsyncRequest(requestId, asyncResult);
        
        // 发送请求（非阻塞）
        rpcClient.sendRequestAsync(request, requestId);
        
        return (AsyncRpcResult<Object>) asyncResult;
    }
    
    /**
     * 生成唯一的请求 ID
     */
    private long generateRequestId() {
        return System.nanoTime() + Thread.currentThread().getId();
    }
    
    /**
     * 提取泛型类型（简化版，实际需要使用 Type 解析）
     */
    private Class<?> extractGenericType(Class<?> clazz) {
        // 简化处理：直接返回 Object
        // 完整实现需要解析 Method.getGenericReturnType()
        return Object.class;
    }
}
```

**说明**：
- 自动识别返回类型，选择调用模式
- 对业务代码完全透明
- 支持三种调用方式

---

### 3.5 增强 RpcNettyClient 支持异步发送

**文件路径**：`rpc-core/src/main/java/com/rpc/transport/netty/client/RpcNettyClient.java`

需要在现有类中添加异步发送方法：

```java
/**
 * 异步发送请求（不阻塞）
 * @param request RPC 请求
 * @param requestId 请求 ID
 * @throws Exception 发送异常
 */
public void sendRequestAsync(RpcRequest request, long requestId) throws Exception {
    // 1. 从注册中心获取服务提供者列表
    List<InetSocketAddress> addresses = serviceRegistry.lookup(request.getServiceName());
    
    if (addresses == null || addresses.isEmpty()) {
        throw new RpcException(ErrorCode.SERVICE_NOT_FOUND,
                "服务未找到：" + request.getServiceName());
    }
    
    // 2. 负载均衡选择一个实例
    InetSocketAddress selectedAddress = loadBalancer.select(addresses, request);
    
    // 3. 获取或创建连接
    Channel channel = connectionManager.getChannel(selectedAddress);
    
    if (channel == null || !channel.isActive()) {
        throw new RpcException(ErrorCode.CHANNEL_UNAVAILABLE,
                "无法连接到服务：" + selectedAddress);
    }
    
    // 4. 构建完整的 RpcMessage
    RpcHeader header = buildRpcHeader(requestId, RpcMessageType.REQUEST);
    RpcMessage message = RpcMessage.builder()
            .header(header)
            .body(request)
            .build();
    
    // 5. 异步写入 Channel（立即返回，不阻塞）
    channel.writeAndFlush(message).addListener(future -> {
        if (!future.isSuccess()) {
            // 发送失败，完成 Future
            requestManager.failRequest(requestId, future.cause());
            log.error("发送请求失败：requestId={}", requestId, future.cause());
        } else {
            log.debug("请求发送成功：requestId={}", requestId);
        }
    });
    
    // 注意：这里不等待响应，立即返回
    // 响应会由 ResponseHandler 异步处理
}
```

---

## 四、使用示例

### 4.1 服务端改造（零改动）

服务端**完全不需要修改**！因为异步调用只是客户端的行为模式，服务端依然正常处理请求并返回响应。

```java
// 服务提供方代码（保持不变）
public class HelloServiceImpl implements HelloService {
    @Override
    public User getUserById(Long id) {
        User user = new User();
        user.setId(id);
        user.setName("张三");
        return user;
    }
    
    @Override
    public String sayHello(String name) {
        return "Hello, " + name;
    }
}
```

---

### 4.2 客户端使用示例

#### 示例 1：同步调用（保持兼容）

```java
// 创建代理（使用原来的 RpcProxyFactory）
HelloService helloService = RpcProxyFactory.create(HelloService.class);

// 同步调用（阻塞）
User user = helloService.getUserById(1L);
System.out.println("用户：" + user);
```

**字节码增强方案**：如果要支持自动识别，需要通过 CGLIB 增强接口，为每个方法生成对应的异步版本。更简单的方式是直接在接口中定义异步方法。

---

#### 示例 2：异步调用 - CompletableFuture 版本

**步骤 1**：在接口中定义异步方法

```java
// example-api/src/main/java/com/rpc/HelloService.java
public interface HelloService {
    // 同步方法
    User getUserById(Long id);
    String sayHello(String name);
    
    // ========== 异步方法（新增） ==========
    
    /**
     * 异步获取用户（返回 CompletableFuture）
     */
    CompletableFuture<User> getUserByIdAsync(Long id);
    
    /**
     * 异步打招呼（返回 CompletableFuture）
     */
    CompletableFuture<String> sayHelloAsync(String name);
}
```

**步骤 2**：服务实现类（Provider 端）

```java
// Provider 端实现（只需要实现同步方法即可）
public class HelloServiceImpl implements HelloService {
    @Override
    public User getUserById(Long id) {
        User user = new User();
        user.setId(id);
        user.setName("张三");
        return user;
    }
    
    @Override
    public String sayHello(String name) {
        return "Hello, " + name;
    }
    
    // 异步方法可以不用实现，由代理层自动处理
    // 如果必须实现（编译需要），可以抛出 UnsupportedOperationException
    @Override
    public CompletableFuture<User> getUserByIdAsync(Long id) {
        throw new UnsupportedOperationException("异步方法由客户端代理实现");
    }
    
    @Override
    public CompletableFuture<String> sayHelloAsync(String name) {
        throw new UnsupportedOperationException("异步方法由客户端代理实现");
    }
}
```

**步骤 3**：客户端调用

```java
// Consumer 端
HelloService helloService = RpcProxyFactory.create(HelloService.class);

// 异步调用 1：立即返回，不阻塞
CompletableFuture<User> future = helloService.getUserByIdAsync(1L);

// 做其他事情...
System.out.println("正在处理其他业务...");

// 需要时再获取结果（可选）
future.thenAccept(user -> {
    System.out.println("收到用户：" + user);
}).exceptionally(ex -> {
    System.err.println("调用失败：" + ex.getMessage());
    return null;
});

// 如果需要阻塞等待（最多等 5 秒）
try {
    User user = future.get(5, TimeUnit.SECONDS);
    System.out.println("获取到用户：" + user);
} catch (TimeoutException e) {
    System.err.println("调用超时");
}
```

---

#### 示例 3：异步调用 - 回调版本

**步骤 1**：接口定义

```java
public interface HelloService {
    // ... 同步方法省略 ...
    
    /**
     * 异步获取用户（带回调）
     */
    void getUserByIdWithCallback(Long id, RpcCallback<User> callback);
}
```

**步骤 2**：客户端调用

```java
HelloService helloService = RpcProxyFactory.create(HelloService.class);

// 方式 1：匿名内部类
helloService.getUserByIdWithCallback(1L, new RpcCallback<User>() {
    @Override
    public void onSuccess(User user) {
        System.out.println("收到用户：" + user);
    }
    
    @Override
    public void onFailure(Throwable cause) {
        System.err.println("调用失败：" + cause.getMessage());
    }
});

// 方式 2：Lambda 表达式（更简洁）
helloService.getUserByIdWithCallback(1L, 
    user -> System.out.println("收到用户：" + user),
    ex -> System.err.println("调用失败：" + ex.getMessage())
);

// 继续做其他事情...
System.out.println("请求已发送，等待回调...");
Thread.sleep(2000); // 等待回调完成
```

---

#### 示例 4：AsyncRpcResult 版本（更灵活）

**接口定义**：

```java
public interface HelloService {
    // ... 其他方法省略 ...
    
    /**
     * 异步获取用户（返回 AsyncRpcResult）
     */
    AsyncRpcResult<User> getUserByIdAsync2(Long id);
}
```

**客户端调用**：

```java
HelloService helloService = RpcProxyFactory.create(HelloService.class);

// 调用 1：阻塞获取结果
AsyncRpcResult<User> result1 = helloService.getUserByIdAsync2(1L);
User user1 = result1.get(); // 阻塞，最多等 30 秒
System.out.println("用户 1:" + user1);

// 调用 2：带超时的阻塞
AsyncRpcResult<User> result2 = helloService.getUserByIdAsync2(2L);
User user2 = result2.get(5, TimeUnit.SECONDS); // 最多等 5 秒
System.out.println("用户 2:" + user2);

// 调用 3：注册回调（非阻塞）
AsyncRpcResult<User> result3 = helloService.getUserByIdAsync2(3L);
result3.addCallback(new RpcCallback<User>() {
    @Override
    public void onSuccess(User user) {
        System.out.println("回调收到用户：" + user);
    }
    
    @Override
    public void onFailure(Throwable cause) {
        System.err.println("回调失败：" + cause.getMessage());
    }
});

// 主线程继续工作...
Thread.sleep(2000);
```

---

#### 示例 5：One-Way 调用（不等待响应）

**接口定义**：

```java
public interface LogService {
    /**
     * 记录日志（One-Way 调用）
     */
    void logInfo(String message);
    
    /**
     * 标记为 One-Way 的注解（可选）
     */
    @RpcOneway
    void logDebug(String message);
}
```

**客户端调用**：

```java
LogService logService = RpcProxyFactory.create(LogService.class);

// One-Way 调用：立即返回，不等待响应
logService.logInfo("这是一条日志");

// 继续执行后续代码，不阻塞
System.out.println("日志已发送");
```

**实现思路**：
- 检测方法的 `@RpcOneway` 注解
- 或者根据返回类型 `void` 判断
- 发送请求后不调用 `requestManager.addRequest()`
- Channel 写入后立即返回

---

#### 示例 6：并行调用与结果合并

**场景**：同时获取用户、订单、商品信息

```java
// 注入多个服务
UserService userService = RpcProxyFactory.create(UserService.class);
OrderService orderService = RpcProxyFactory.create(OrderService.class);
ProductService productService = RpcProxyFactory.create(ProductService.class);

Long userId = 1L;

// 并行发起 3 个请求（几乎同时）
CompletableFuture<User> userFuture = userService.getUserByIdAsync(userId);
CompletableFuture<Order> orderFuture = orderService.getOrderAsync(userId);
CompletableFuture<Product> productFuture = productService.getProductAsync(userId);

// 等待所有完成
CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        userFuture, orderFuture, productFuture);

allFutures.thenRun(() -> {
    try {
        // 获取所有结果
        User user = userFuture.get();
        Order order = orderFuture.get();
        Product product = productFuture.get();
        
        System.out.println("用户：" + user);
        System.out.println("订单：" + order);
        System.out.println("商品：" + product);
        
    } catch (Exception e) {
        System.err.println("获取结果失败：" + e.getMessage());
    }
}).exceptionally(ex -> {
    System.err.println("并行调用失败：" + ex.getMessage());
    return null;
});

// 主线程可以继续工作...
```

**优势**：
- 总耗时 = `max(t1, t2, t3)` 而不是 `t1 + t2 + t3`
- 性能提升显著（特别是跨服务调用）

---

## 五、完整测试案例

### 5.1 单元测试

**文件路径**：`rpc-core/src/test/java/com/rpc/async/AsyncRpcTest.java`

```java
package com.rpc.async;

import com.rpc.protocol.RpcResponse;
import com.rpc.transport.netty.client.manager.RequestManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步 RPC 功能测试
 */
public class AsyncRpcTest {
    
    @Test
    public void testAsyncRpcResult_Success() throws Exception {
        // 给定
        RequestManager requestManager = new RequestManager();
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);
        
        long requestId = 123L;
        requestManager.addAsyncRequest(requestId, asyncResult);
        
        // 模拟收到响应
        RpcResponse response = RpcResponse.success("Hello World", String.valueOf(requestId));
        requestManager.completeResponse(response);
        
        // 当
        String result = asyncResult.get();
        
        // 断言
        assertEquals("Hello World", result);
        assertTrue(asyncResult.isDone());
    }
    
    @Test
    public void testAsyncRpcResult_Failure() {
        // 给定
        RequestManager requestManager = new RequestManager();
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);
        
        long requestId = 456L;
        requestManager.addAsyncRequest(requestId, asyncResult);
        
        // 模拟异常
        RuntimeException exception = new RuntimeException("网络错误");
        requestManager.failRequest(requestId, exception);
        
        // 当 & 断言
        assertThrows(Exception.class, () -> asyncResult.get());
        assertTrue(asyncResult.isDone());
    }
    
    @Test
    public void testCallback_Success() throws Exception {
        // 给定
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);
        
        RpcCallback<String> callback = new RpcCallback<String>() {
            @Override
            public void onSuccess(String result) {
                System.out.println("回调收到：" + result);
                callbackCalled.set(true);
                latch.countDown();
            }
            
            @Override
            public void onFailure(Throwable cause) {
                fail("不应该失败");
            }
        };
        
        asyncResult.addCallback(callback);
        
        // 触发完成
        asyncResult.setResult(RpcResponse.success("Test Result", "1"));
        
        // 等待回调
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(callbackCalled.get());
    }
    
    @Test
    public void testCallback_Failure() throws Exception {
        // 给定
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean failureCalled = new AtomicBoolean(false);
        
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);
        
        RpcCallback<String> callback = new RpcCallback<String>() {
            @Override
            public void onSuccess(String result) {
                fail("不应该成功");
            }
            
            @Override
            public void onFailure(Throwable cause) {
                System.out.println("回调失败：" + cause.getMessage());
                failureCalled.set(true);
                latch.countDown();
            }
        };
        
        asyncResult.addCallback(callback);
        
        // 触发异常
        asyncResult.setException(new RuntimeException("测试异常"));
        
        // 等待回调
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(failureCalled.get());
    }
    
    @Test
    public void testToCompletableFuture() throws Exception {
        // 给定
        AsyncRpcResult<String> asyncResult = new AsyncRpcResult<>(String.class);
        CompletableFuture<String> future = asyncResult.toCompletableFuture();
        
        // 当
        asyncResult.setResult(RpcResponse.success("Future Test", "1"));
        String result = future.get(3, TimeUnit.SECONDS);
        
        // 断言
        assertEquals("Future Test", result);
    }
}
```

---

### 5.2 集成测试

**文件路径**：`rpc-core/src/test/java/com/rpc/async/AsyncIntegrationTest.java`

```java
package com.rpc.async;

import com.rpc.HelloService;
import com.rpc.model.User;
import com.rpc.proxy.impl.RpcProxyFactory;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.transport.netty.server.RpcNettyServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步 RPC 集成测试
 */
public class AsyncIntegrationTest {
    
    private static RpcNettyServer server;
    private static RpcNettyClient client;
    private static HelloService helloService;
    
    @BeforeAll
    public static void setup() throws Exception {
        // 启动服务端
        server = new RpcNettyServer();
        server.start();
        
        // 启动客户端
        client = new RpcNettyClient();
        client.init();
        
        // 创建代理
        helloService = RpcProxyFactory.create(HelloService.class, client);
    }
    
    @AfterAll
    public static void teardown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop();
    }
    
    @Test
    public void testCompletableFuture_Async() throws Exception {
        // 异步调用
        CompletableFuture<User> future = helloService.getUserByIdAsync(1L);
        
        // 等待完成
        User user = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(user);
        assertEquals(1L, user.getId());
    }
    
    @Test
    public void testCallback_Async() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<User> capturedUser = new AtomicReference<>();
        
        // 回调调用
        helloService.getUserByIdWithCallback(2L, new RpcCallback<User>() {
            @Override
            public void onSuccess(User user) {
                capturedUser.set(user);
                latch.countDown();
            }
            
            @Override
            public void onFailure(Throwable cause) {
                fail("调用失败：" + cause.getMessage());
            }
        });
        
        // 等待回调
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(capturedUser.get());
        assertEquals(2L, capturedUser.get().getId());
    }
    
    @Test
    public void testParallel_Calls() throws Exception {
        // 并行发起 3 个请求
        CompletableFuture<User> f1 = helloService.getUserByIdAsync(1L);
        CompletableFuture<User> f2 = helloService.getUserByIdAsync(2L);
        CompletableFuture<User> f3 = helloService.getUserByIdAsync(3L);
        
        // 等待所有完成
        CompletableFuture.allOf(f1, f2, f3).join();
        
        // 验证结果
        assertEquals(1L, f1.get().getId());
        assertEquals(2L, f2.get().getId());
        assertEquals(3L, f3.get().getId());
    }
}
```

---

## 六、性能对比

### 6.1 同步 vs 异步 性能测试

```java
@Test
public void benchmark_SyncVsAsync() throws Exception {
    int iterations = 100;
    
    // 同步调用 100 次（串行）
    long syncStart = System.currentTimeMillis();
    for (int i = 0; i < iterations; i++) {
        helloService.getUserById((long) i);
    }
    long syncTime = System.currentTimeMillis() - syncStart;
    
    // 异步调用 100 次（并行）
    long asyncStart = System.currentTimeMillis();
    CompletableFuture<?>[] futures = new CompletableFuture[iterations];
    for (int i = 0; i < iterations; i++) {
        futures[i] = helloService.getUserByIdAsync((long) i);
    }
    CompletableFuture.allOf(futures).join();
    long asyncTime = System.currentTimeMillis() - asyncStart;
    
    System.out.println("同步耗时：" + syncTime + "ms");
    System.out.println("异步耗时：" + asyncTime + "ms");
    System.out.println("性能提升：" + (syncTime * 1.0 / asyncTime));
}
```

**预期结果**：
- 同步：~5000ms（假设每次 50ms）
- 异步：~100ms（并行执行，取决于最慢的那个）
- **性能提升约 50 倍！**

---

## 七、最佳实践

### 7.1 何时使用异步？

✅ **适合异步的场景**：
- 批量数据处理
- 多个独立服务调用
- 实时性要求不高
- UI 线程（避免卡顿）
- 日志记录

❌ **不适合异步的场景**：
- 强事务一致性要求
- 严格顺序依赖
- 调试困难的问题排查

---

### 7.2 避免常见陷阱

#### 陷阱 1：忘记处理异常

```java
// ❌ 错误示例
CompletableFuture<User> future = helloService.getUserByIdAsync(1L);
future.thenAccept(user -> System.out.println(user));
// 如果失败了怎么办？没有处理！

// ✅ 正确示例
helloService.getUserByIdAsync(1L)
    .thenAccept(user -> System.out.println(user))
    .exceptionally(ex -> {
        System.err.println("调用失败：" + ex.getMessage());
        return null;
    });
```

#### 陷阱 2：过度使用 get()

```java
// ❌ 错误示例（异步变同步）
CompletableFuture<User> future = helloService.getUserByIdAsync(1L);
User user = future.get(); // 阻塞！失去了异步意义

// ✅ 正确示例（使用回调）
helloService.getUserByIdAsync(1L)
    .thenAccept(this::processUser); // 非阻塞

private void processUser(User user) {
    // 处理逻辑
}
```

#### 陷阱 3：回调地狱

```java
// ❌ 错误示例（嵌套过深）
service1.callAsync()
    .thenAccept(r1 -> {
        service2.callAsync(r1)
            .thenAccept(r2 -> {
                service3.callAsync(r2)
                    .thenAccept(r3 -> {
                        // ... 嵌套深渊
                    });
            });
    });

// ✅ 正确示例（链式调用）
service1.callAsync()
    .thenApply(r1 -> transform1(r1))
    .thenCompose(r2 -> service2.callAsync(r2))
    .thenApply(r3 -> transform2(r3))
    .thenCompose(r4 -> service3.callAsync(r4))
    .thenAccept(finalResult -> process(finalResult))
    .exceptionally(ex -> handleError(ex));
```

---

### 7.3 超时设置建议

```java
// 推荐：根据业务重要性设置不同超时
AsyncRpcResult<User> fastResult = helloService.getUserByIdAsync(1L);
User user = fastResult.get(2, TimeUnit.SECONDS); // 快速失败

AsyncRpcResult<Order> importantResult = orderService.createOrder(order);
Order order = importantResult.get(30, TimeUnit.SECONDS); // 重要操作，多等会
```

---

## 八、总结

### 8.1 核心要点回顾

1. **异步的优势**：
   - 提高吞吐量
   - 降低延迟
   - 改善用户体验

2. **三种调用模式**：
   - CompletableFuture：链式回调，函数式风格
   - RpcCallback：传统回调接口
   - AsyncRpcResult：封装版，更灵活

3. **设计亮点**：
   - 低耦合：业务代码无需关心网络细节
   - 高内聚：异步功能集中在独立模块
   - 易使用：只需几行代码即可使用

---

### 8.2 下一步学习方向

- [x] 本课内容：异步调用与回调
- ➡️ 下一课：整合 Spring Boot（@RpcService、@RpcReference 注解）
- ➡️ 性能优化：连接池、缓冲区、零拷贝
- ➡️ 监控与追踪：链路追踪、指标收集

---

## 九、课后练习

### 练习 1：实现批量查询

编写一个方法，批量查询用户信息（10 个 ID），要求：
- 并行发起请求
- 等待所有完成后返回结果列表
- 处理部分失败的情况

### 练习 2：实现超时降级

为异步调用添加超时降级逻辑：
- 超过 3 秒未完成则触发降级
- 返回缓存数据或默认值
- 记录降级日志

### 练习 3：实现重试机制

结合第 12 课的熔断降级，实现异步重试：
- 失败后自动重试（最多 3 次）
- 指数退避策略
- 重试失败后触发熔断

---

## 十、参考资料

- [Java CompletableFuture 官方文档](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html)
- [Netty 异步编程最佳实践](https://netty.io/wiki/user-guide-for-4.x.html)
- [Reactive Streams 规范](http://www.reactive-streams.org/)

---

**恭喜完成第 13 课！** 🎉

现在你已经掌握了 RPC 异步调用的核心原理和实现技巧。在下一课中，我们将学习如何整合 Spring Boot，让 RPC 框架的使用更加简单优雅！
