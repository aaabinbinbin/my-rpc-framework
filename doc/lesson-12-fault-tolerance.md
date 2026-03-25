# 第 12 课：容错与重试机制

## 课程目标

本节课将带你实现 RPC 框架的容错与重试机制，包括：
- ✅ 失败自动重试（可配置重试次数）
- ✅ 超时控制（连接超时、读取超时）
- ✅ 熔断降级策略（快速失败、故障隔离）
- ✅ 异常分类与处理
- ✅ 优雅的服务调用体验

---

## 一、为什么需要容错机制？

### 1.1 分布式系统的必然性

在分布式环境中，网络调用失败是**常态**而不是异常：
- 网络抖动、瞬时拥塞
- 服务端临时不可用
- 服务重启、升级
- 负载均衡器切换节点

**没有容错机制的 RPC 框架 = 脆弱不堪**

### 1.2 常见问题场景

```
场景 1：网络瞬时抖动
客户端 ----[请求]----> 服务端
         [响应]× (丢失)
结果：调用失败，但实际服务已执行 → 需要重试

场景 2：服务端短暂不可用
客户端 ----[请求]----> 服务端 (宕机中)
结果：连接拒绝 → 需要切换到其他节点

场景 3：服务处理超时
客户端 ----[请求]----> 服务端 (处理慢)
         [等待...] ⏰ 超时
结果：客户端放弃，但服务端可能还在执行 → 需要幂等性保证
```

---

## 二、异常分类体系

### 2.1 设计思路

不同的异常需要不同的处理策略：
- **可重试异常**：网络抖动、超时等临时性问题
- **不可重试异常**：参数错误、方法不存在等业务问题
- **需要熔断异常**：服务端持续失败、资源耗尽等严重问题

### 2.2 代码实现

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/common/RpcException.java`

```java
package com.rpc.common;

import lombok.Getter;

/**
 * RPC 异常基类
 * 所有 RPC 相关异常都应继承此类
 */
@Getter
public class RpcException extends RuntimeException {
    
    /** 错误码 */
    private final ErrorCode errorCode;
    
    /** 是否可重试 */
    private final boolean retryable;
    
    public RpcException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = errorCode.isRetryable();
    }
    
    public RpcException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = errorCode.isRetryable();
    }
}
```

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/common/ErrorCode.java`

```java
package com.rpc.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RPC 错误码枚举
 * 定义所有可能的错误类型及其属性
 */
@AllArgsConstructor
@Getter
public enum ErrorCode {
    
    // ========== 成功 ==========
    SUCCESS(0, "成功", true),
    
    // ========== 客户端异常（不可重试） ==========
    ILLEGAL_ARGUMENT(1001, "参数非法", false),
    SERVICE_NOT_FOUND(1002, "服务未找到", false),
    METHOD_NOT_FOUND(1003, "方法未找到", false),
    SERIALIZATION_ERROR(1004, "序列化失败", false),
    
    // ========== 网络异常（可重试） ==========
    NETWORK_TIMEOUT(2001, "网络超时", true),
    CONNECTION_REFUSED(2002, "连接被拒绝", true),
    CONNECTION_RESET(2003, "连接被重置", true),
    CHANNEL_UNAVAILABLE(2004, "通道不可用", true),
    
    // ========== 服务端异常（部分可重试） ==========
    SERVER_BUSY(3001, "服务器繁忙", true),
    SERVER_ERROR(3002, "服务器内部错误", true),
    SERVICE_EXCEPTION(3003, "服务执行异常", false),
    
    // ========== 熔断降级（不可重试） ==========
    CIRCUIT_BREAKER_OPEN(4001, "熔断器已打开", false),
    RATE_LIMIT_EXCEEDED(4002, "超过限流阈值", false),
    SERVICE_DEGRADED(4003, "服务已降级", false);
    
    /** 错误码数值 */
    private final int code;
    
    /** 错误描述 */
    private final String description;
    
    /** 是否可重试 */
    private final boolean retryable;
}
```

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/common/TimeoutException.java`

```java
package com.rpc.common;

/**
 * 超时异常
 * 专门用于处理各种超时场景
 */
public class TimeoutException extends RpcException {
    
    public TimeoutException(String message) {
        super(ErrorCode.NETWORK_TIMEOUT, message);
    }
    
    public TimeoutException(String message, Throwable cause) {
        super(ErrorCode.NETWORK_TIMEOUT, message, cause);
    }
}
```

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/common/CircuitBreakerException.java`

```java
package com.rpc.common;

/**
 * 熔断器异常
 * 当熔断器打开时抛出此异常
 */
public class CircuitBreakerException extends RpcException {
    
    public CircuitBreakerException(String serviceName) {
        super(ErrorCode.CIRCUIT_BREAKER_OPEN, 
              "服务 [" + serviceName + "] 熔断器已打开，拒绝访问");
    }
}
```

---

## 三、重试机制实现

### 3.1 设计思路

重试机制的核心要素：
1. **重试条件判断**：不是所有失败都值得重试
2. **重试次数控制**：避免无限重试
3. **退避策略**：指数退避避免雪崩
4. **快速失败**：达到上限立即返回

### 3.2 重试策略接口

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/retry/RetryStrategy.java`

```java
package com.rpc.faulttolerance.retry;

import com.rpc.common.RpcException;

/**
 * 重试策略接口
 * 定义重试决策的标准
 */
public interface RetryStrategy {
    
    /**
     * 判断是否应该重试
     * @param exception 抛出的异常
     * @param currentRetry 当前重试次数（从 0 开始）
     * @param maxRetries 最大重试次数
     * @return true-重试，false-放弃
     */
    boolean shouldRetry(RpcException exception, int currentRetry, int maxRetries);
    
    /**
     * 计算下次重试的延迟时间（毫秒）
     * @param currentRetry 当前重试次数
     * @return 延迟时间
     */
    long getDelay(int currentRetry);
}
```

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/retry/DefaultRetryStrategy.java`

```java
package com.rpc.faulttolerance.retry;

import com.rpc.common.ErrorCode;
import com.rpc.common.RpcException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认重试策略实现
 * 特性：
 * 1. 仅对可重试异常进行重试
 * 2. 指数退避算法
 * 3. 添加随机抖动避免惊群效应
 */
@Slf4j
public class DefaultRetryStrategy implements RetryStrategy {
    
    /** 基础延迟时间（毫秒） */
    private static final long BASE_DELAY_MS = 100;
    
    /** 最大延迟时间（毫秒） */
    private static final long MAX_DELAY_MS = 5000;
    
    /** 退避因子 */
    private static final double BACKOFF_MULTIPLIER = 2.0;
    
    /** 随机抖动范围（±20%） */
    private static final double JITTER_FACTOR = 0.2;
    
    @Override
    public boolean shouldRetry(RpcException exception, int currentRetry, int maxRetries) {
        // 1. 检查是否达到最大重试次数
        if (currentRetry >= maxRetries) {
            log.debug("已达到最大重试次数 {}，不再重试", maxRetries);
            return false;
        }
        
        // 2. 检查异常是否可重试
        if (!exception.isRetryable()) {
            log.debug("异常不可重试：{}", exception.getErrorCode());
            return false;
        }
        
        // 3. 根据错误码精细控制
        ErrorCode errorCode = exception.getErrorCode();
        switch (errorCode) {
            case NETWORK_TIMEOUT:
            case CONNECTION_REFUSED:
            case CONNECTION_RESET:
            case CHANNEL_UNAVAILABLE:
                log.info("网络异常，准备重试：{} (第{}/{}次)", 
                        errorCode.getDescription(), currentRetry + 1, maxRetries);
                return true;
                
            case SERVER_BUSY:
            case SERVER_ERROR:
                // 服务端错误可以尝试，但要谨慎
                log.info("服务端异常，准备重试：{} (第{}/{}次)", 
                        errorCode.getDescription(), currentRetry + 1, maxRetries);
                return true;
                
            default:
                log.debug("错误码不支持重试：{}", errorCode);
                return false;
        }
    }
    
    @Override
    public long getDelay(int currentRetry) {
        // 指数退避公式：delay = baseDelay * (multiplier ^ retryCount)
        long delay = (long) (BASE_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, currentRetry));
        
        // 限制最大延迟
        delay = Math.min(delay, MAX_DELAY_MS);
        
        // 添加随机抖动：delay * (1 ± jitterFactor)
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble(-JITTER_FACTOR, JITTER_FACTOR));
        delay = (long) (delay * jitter);
        
        log.debug("计算重试延迟：第{}次，delay={}ms", currentRetry, delay);
        return delay;
    }
}
```

### 3.3 重试执行器

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/retry/RetryExecutor.java`

```java
package com.rpc.faulttolerance.retry;

import com.rpc.common.RpcException;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 重试执行器
 * 封装重试逻辑，提供统一的重试入口
 */
@Slf4j
public class RetryExecutor {
    
    /** 重试策略 */
    private final RetryStrategy retryStrategy;
    
    /** 最大重试次数 */
    private final int maxRetries;
    
    public RetryExecutor(RetryStrategy retryStrategy, int maxRetries) {
        this.retryStrategy = retryStrategy;
        this.maxRetries = maxRetries;
    }
    
    /**
     * 执行带重试的 RPC 调用
     * @param request RPC 请求
     * @param callable 实际调用逻辑
     * @return RPC 响应
     * @throws Exception 重试失败后抛出的异常
     */
    public RpcResponse executeWithRetry(RpcRequest request, 
                                        Callable<RpcResponse> callable) throws Exception {
        int retryCount = 0;
        RpcException lastException = null;
        
        while (true) {
            try {
                // 执行实际调用
                log.debug("执行 RPC 调用：{}.{} (尝试第{}次)", 
                        request.getServiceName(), 
                        request.getMethodName(), 
                        retryCount + 1);
                
                return callable.call();
                
            } catch (RpcException e) {
                lastException = e;
                log.warn("RPC 调用失败：{} - {}", e.getErrorCode(), e.getMessage());
                
                // 判断是否重试
                if (retryStrategy.shouldRetry(e, retryCount, maxRetries)) {
                    retryCount++;
                    
                    // 计算延迟时间
                    long delay = retryStrategy.getDelay(retryCount);
                    log.info("将在 {}ms 后重试 (第{}/{}次)...", delay, retryCount, maxRetries);
                    
                    // 等待延迟
                    TimeUnit.MILLISECONDS.sleep(delay);
                    
                } else {
                    // 不重试，直接抛出
                    log.error("放弃重试，总共失败{}次", retryCount + 1);
                    throw e;
                }
            } catch (Exception e) {
                // 非 RpcException，包装后重试
                log.error("未知异常", e);
                RpcException wrapped = new RpcException(ErrorCode.SERVER_ERROR, 
                        "未知异常：" + e.getMessage(), e);
                
                if (retryStrategy.shouldRetry(wrapped, retryCount, maxRetries)) {
                    retryCount++;
                    long delay = retryStrategy.getDelay(retryCount);
                    TimeUnit.MILLISECONDS.sleep(delay);
                } else {
                    throw e;
                }
            }
        }
    }
}
```

---

## 四、熔断器实现（核心重点）

### 4.1 熔断器模式简介

熔断器有三种状态：
1. **CLOSED（关闭）**：正常状态，允许请求通过
2. **OPEN（打开）**：熔断状态，拒绝所有请求
3. **HALF_OPEN（半开）**：探测状态，允许少量请求测试

```
状态转换图：

    [CLOSED] ---失败率超阈值---> [OPEN]
       ^                            |
       |                            | 休眠时间结束
       |                            v
       |                       [HALF_OPEN]
       |                            |
       |--------------------成功----|
                                    |
                                    |---失败---> [OPEN]
```

### 4.2 为什么需要熔断器？

**问题场景：**

假设你的 RPC 框架调用了一个下游服务，该服务突然响应变慢或不可用：

```
没有熔断器的情况：
┌──────────┐
│  客户端 A │ ──────┐
└──────────┘       │
┌──────────┐       │
│  客户端 B │ ──────┼──→ [下游服务] ❌ (响应慢/不可用)
└──────────┘       │
┌──────────┐       │
│  客户端 C │ ──────┘
└──────────┘

结果：
1. 所有客户端持续发送请求
2. 下游服务压力越来越大
3. 最终彻底崩溃
4. 所有客户端也跟着失败（雪崩效应）
```

**有熔断器的保护：**

```
┌──────────┐
│  客户端 A │ ──× (熔断器打开，快速失败)
└──────────┘
┌──────────┐
│  客户端 B │ ──× (返回降级数据)
└──────────┘       
┌──────────┐       
│  客户端 C │ ──○ (半开状态，放行 1 个探测)
└──────────┘       ↓
              [下游服务] 🔄 (恢复中...)
              
好处：
1. 保护下游服务，给它恢复时间
2. 客户端快速失败，不浪费资源等待
3. 自动探测恢复，无需人工干预
```

### 4.3 熔断器接口设计

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/CircuitBreaker.java`

```java
package com.rpc.faulttolerance;

/**
 * 熔断器接口
 * 定义熔断器的基本行为
 */
public interface CircuitBreaker {
    
    /**
     * 判断是否允许请求通过
     * @return true-允许，false-拒绝
     */
    boolean allowRequest();
    
    /**
     * 记录成功调用
     */
    void recordSuccess();
    
    /**
     * 记录失败调用
     */
    void recordFailure();
    
    /**
     * 获取当前状态
     * @return 熔断器状态
     */
    CircuitBreakerState getState();
    
    /**
     * 手动重置熔断器
     */
    void reset();
}
```

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/CircuitBreakerState.java`

```java
package com.rpc.faulttolerance;

/**
 * 熔断器状态枚举
 */
public enum CircuitBreakerState {
    /** 关闭状态 - 正常 */
    CLOSED,
    
    /** 打开状态 - 熔断 */
    OPEN,
    
    /** 半开状态 - 探测 */
    HALF_OPEN
}
```

### 4.4 熔断器核心实现详解

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreakerImpl.java`

```java
package com.rpc.faulttolerance.circuitbreaker;

import com.rpc.faulttolerance.CircuitBreaker;
import com.rpc.faulttolerance.CircuitBreakerState;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 熔断器实现
 * 基于滑动窗口统计失败率
 */
@Slf4j
public class CircuitBreakerImpl implements CircuitBreaker {
    /** 服务名称 */
    private final String serviceName;

    /** 失败率阈值（百分比） */
    private final float failureRateThreshold;

    /** 最小请求数（达到此数量才开始统计） */
    private final int minNumberOfCalls;

    /** 熔断器打开后的休眠时间（毫秒） */
    private final long waitDurationInOpenState;

    /** 半开状态允许的最大请求数 */
    private final int permittedNumberOfCallsInHalfOpenState;

    // ========== 统计数据 ==========

    /** 总请求数（滑动窗口） */
    private final AtomicInteger totalCalls = new AtomicInteger(0);

    /** 失败请求数（滑动窗口） */
    private final AtomicInteger failedCalls = new AtomicInteger(0);

    /** 熔断器状态 */
    private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;

    /** 熔断器打开的时间戳 */
    private volatile long lastFailureTime = 0;

    /** 半开状态已通过的请求数 */
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);

    public CircuitBreakerImpl(String serviceName,
                              float failureRateThreshold,
                              int minNumberOfCalls,
                              long waitDurationInOpenState,
                              int permittedNumberOfCallsInHalfOpenState) {
        this.serviceName = serviceName;
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
    }
    
    // ... 具体方法实现见下方详解
}
```

#### 核心方法 1：allowRequest() - 决定是否放行请求

```java
@Override
public boolean allowRequest() {
    CircuitBreakerState currentState = getState();

    if (currentState == CircuitBreakerState.OPEN) {
        // 检查是否可以进入半开状态
        long elapsed = System.currentTimeMillis() - lastFailureTime;
        if (elapsed >= waitDurationInOpenState) {
            log.info("熔断器从 OPEN 进入 HALF_OPEN: {}", serviceName);
            state = CircuitBreakerState.HALF_OPEN;
            halfOpenCalls.set(0);
            return true;  // 允许探测请求
        }
        return false;  // 熔断期间拒绝所有请求
    }

    if (currentState == CircuitBreakerState.HALF_OPEN) {
        // 半开状态限制请求数
        int currentCalls = halfOpenCalls.incrementAndGet();
        if (currentCalls <= permittedNumberOfCallsInHalfOpenState) {
            return true;  // 前 N 个请求允许通过（探测）
        }
        log.debug("半开状态请求数超限，拒绝：{}", serviceName);
        return false;  // 超过探测数量，继续拒绝
    }

    // CLOSED 状态允许所有请求
    return true;
}
```

**关键点解析：**
1. **OPEN 状态**：直接拒绝，除非过了休眠期才转为 HALF_OPEN
2. **HALF_OPEN 状态**：只允许有限数量的请求通过（用于探测服务是否恢复）
3. **CLOSED 状态**：正常放行

#### 核心方法 2：recordFailure() - 记录失败并可能触发熔断

```java
@Override
public void recordFailure() {
    totalCalls.incrementAndGet();
    failedCalls.incrementAndGet();
    lastFailureTime = System.currentTimeMillis();  // 记录最后失败时间

    CircuitBreakerState currentState = getState();

    if (currentState == CircuitBreakerState.HALF_OPEN) {
        // 半开状态下失败，说明服务还没好，重新熔断
        log.warn("熔断器从 HALF_OPEN 重新进入 OPEN: {}", serviceName);
        state = CircuitBreakerState.OPEN;
    } else if (currentState == CircuitBreakerState.CLOSED) {
        // 关闭状态下检查是否达到阈值
        checkAndUpdateState();
    }
}
```

**触发熔断的条件：**
- 在 CLOSED 状态下
- 失败率达到阈值（如 50%）
- 且总请求数达到最小数量（避免偶然失败）

#### 核心方法 3：recordSuccess() - 记录成功并可能恢复

```java
@Override
public void recordSuccess() {
    totalCalls.incrementAndGet();

    CircuitBreakerState currentState = getState();

    if (currentState == CircuitBreakerState.HALF_OPEN) {
        // 半开状态下成功，说明服务恢复了！
        log.info("熔断器从 HALF_OPEN 进入 CLOSED: {}", serviceName);
        state = CircuitBreakerState.CLOSED;
        resetStatistics();  // 清空统计，重新开始
    }
    // CLOSED 状态下的成功，只记录统计
}
```

#### 核心方法 4：checkAndUpdateState() - 检查是否应该熔断

```java
private void checkAndUpdateState() {
    int total = totalCalls.get();
    int failed = failedCalls.get();

    // 未达到最小请求数，不统计（避免偶然失败误触发）
    if (total < minNumberOfCalls) {
        return;
    }

    // 计算失败率
    float failureRate = (float) failed / total * 100;

    log.debug("熔断器统计：service={}, total={}, failed={}, failureRate={}%",
            serviceName, total, failed, failureRate);

    // 超过阈值，打开熔断器
    if (failureRate >= failureRateThreshold) {
        log.warn("失败率超阈值，熔断器打开：{} (失败率={}%, 阈值={}%)",
                serviceName, failureRate, failureRateThreshold);
        state = CircuitBreakerState.OPEN;
        resetStatistics();
    }
}
```

### 4.5 熔断器管理器

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreakerManager.java`

```java
package com.rpc.faulttolerance.circuitbreaker;

import com.rpc.faulttolerance.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器管理器
 * 为每个服务维护独立的熔断器
 */
@Slf4j
public class CircuitBreakerManager {
    
    /** 单例 */
    private static final CircuitBreakerManager INSTANCE = new CircuitBreakerManager();
    
    /** 服务熔断器缓存 */
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    
    /** 默认配置 */
    private float failureRateThreshold = 50.0f;  // 失败率 50%
    private int minNumberOfCalls = 10;            // 最小请求数
    private long waitDurationInOpenState = 30000; // 休眠 30 秒
    private int permittedNumberOfCallsInHalfOpenState = 5; // 半开允许 5 个请求
    
    private CircuitBreakerManager() {}
    
    public static CircuitBreakerManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取服务的熔断器（懒加载创建）
     */
    public CircuitBreaker getCircuitBreaker(String serviceName) {
        return circuitBreakers.computeIfAbsent(serviceName,
                name -> createCircuitBreaker(name));
    }
    
    /**
     * 创建熔断器
     */
    private CircuitBreaker createCircuitBreaker(String serviceName) {
        log.info("为服务创建熔断器：{}", serviceName);
        return new CircuitBreakerImpl(
                serviceName,
                failureRateThreshold,
                minNumberOfCalls,
                waitDurationInOpenState,
                permittedNumberOfCallsInHalfOpenState
        );
    }
    
    /**
     * 配置全局参数
     */
    public void configure(float failureRateThreshold,
                         int minNumberOfCalls,
                         long waitDurationInOpenState,
                         int permittedNumberOfCallsInHalfOpenState) {
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        
        log.info("熔断器全局配置更新...");
    }
    
    /**
     * 重置指定服务的熔断器
     */
    public void resetCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = circuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
        }
    }
    
    /**
     * 打印所有熔断器状态
     */
    public void printStatus() {
        circuitBreakers.forEach((name, breaker) -> {
            log.info("熔断器状态：service={}, state={}", name, breaker.getState());
        });
    }
}
```

---

## 五、熔断器工作原理深度剖析

### 4.1 熔断器模式简介

熔断器有三种状态：
1. **CLOSED（关闭）**：正常状态，允许请求通过
2. **OPEN（打开）**：熔断状态，拒绝所有请求
3. **HALF_OPEN（半开）**：探测状态，允许少量请求测试

```
状态转换图：

    [CLOSED] ---失败率超阈值---> [OPEN]
       ^                            |
       |                            | 休眠时间结束
       |                            v
       |                       [HALF_OPEN]
       |                            |
       |--------------------成功----|
                                    |
                                    |---失败---> [OPEN]
```

## 五、熔断器工作原理深度剖析

### 5.1 状态机转换详解

让我们通过一个完整的生命周期来理解熔断器的状态转换：

```
时间线：T0 → T1 → T2 → T3 → T4 → T5

T0: 初始状态
   状态：CLOSED
   总请求：0
   失败请求：0
   
T1: 开始有请求，偶尔失败（10 个中失败 2 个）
   状态：CLOSED
   总请求：10
   失败请求：2
   失败率：20% (< 50% 阈值，正常)
   
T2: 服务开始出现问题，失败率上升（20 个中失败 12 个）
   状态：CLOSED → OPEN ⚡️ (触发熔断!)
   总请求：20
   失败请求：12
   失败率：60% (> 50% 阈值，熔断!)
   
T3: 熔断期间（拒绝所有请求）
   状态：OPEN
   持续时间：30 秒（waitDurationInOpenState）
   所有请求被快速拒绝，抛出 CircuitBreakerException
   
T4: 30 秒后，进入半开状态
   状态：OPEN → HALF_OPEN 🔍
   允许最多 5 个请求通过（permittedNumberOfCallsInHalfOpenState）
   
T5a: 如果探测请求成功（连续 5 个都成功）
   状态：HALF_OPEN → CLOSED ✅ (恢复正常!)
   清空统计数据
   服务完全恢复
   
T5b: 如果探测请求失败（任意 1 个失败）
   状态：HALF_OPEN → OPEN ⚡️ (再次熔断!)
   重新开始 30 秒倒计时
```

### 5.2 关键设计决策

#### 为什么需要 `minNumberOfCalls`？

**问题：** 如果只有 1 个请求就失败了，要不要熔断？

**答案：** 不要！可能是网络抖动。

```java
// 示例：避免偶然失败
if (total < minNumberOfCalls) {  // 如 minNumberOfCalls=10
    return;  // 样本太少，不统计
}
```

**实际场景：**
- 系统刚启动，第 1 个请求失败（可能是预热不足）
- 如果没有最小请求数限制，立即熔断 → 过度保护
- 有 10 个请求作为统计基础，更可靠

#### 为什么 HALF_OPEN 要限制请求数？

**问题：** 半开状态为什么要限制只能放过几个请求？

**原因：**
1. **小流量探测**：服务可能还没完全恢复，大量请求会再次压垮它
2. **风险控制**：即使失败，影响范围也有限（仅 5 个请求）
3. **渐进式恢复**：确认服务真的好了再全量放行

```java
// 半开状态的谨慎策略
int currentCalls = halfOpenCalls.incrementAndGet();
if (currentCalls <= permittedNumberOfCallsInHalfOpenState) {  // 如 5
    return true;  // 前 5 个放行探测
}
return false;  // 继续拒绝，等下一批
```

#### 为什么成功和失败的处理不同？

**观察：**
- `recordSuccess()`：只在 HALF_OPEN 时改变状态
- `recordFailure()`：在 CLOSED 和 HALF_OPEN 都可能改变状态

**原因：**
- **成功是渐进的**：需要在 HALF_OPEN 下连续成功多次才能确认恢复
- **失败是敏感的**：一次失败就说明服务还不好（HALF_OPEN），或者问题严重（CLOSED 达阈值）

### 5.3 并发安全性分析

熔断器在高并发环境下的线程安全问题：

```java
// 原子操作保证线程安全
private final AtomicInteger totalCalls = new AtomicInteger(0);
private final AtomicInteger failedCalls = new AtomicInteger(0);
private final AtomicInteger halfOpenCalls = new AtomicInteger(0);

// volatile 保证可见性
private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
private volatile long lastFailureTime = 0;
```

**为什么不需要锁？**
1. 使用 AtomicInteger 进行计数操作（CAS 保证原子性）
2. state 使用 volatile 保证多线程可见
3. 允许短暂的统计误差（高并发下性能优先）

### 5.4 与其他组件的配合

#### 与重试机制的配合

```java
// RpcNettyClient 中的完整流程
public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
    String serviceName = rpcRequest.getServiceName();
    
    // 1. 先检查熔断器
    CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(serviceName);
    if (!circuitBreaker.allowRequest()) {
        throw new CircuitBreakerException(serviceName);  // 快速失败，不重试
    }
    
    try {
        // 2. 使用重试执行器
        return retryExecutor.executeWithRetry(rpcRequest, 
            () -> doSendRequest(rpcRequest, circuitBreaker));
            
    } catch (RpcException e) {
        // 3. 重试失败后，记录到熔断器
        circuitBreaker.recordFailure();
        throw e;
    }
}
```

**配合逻辑：**
1. 熔断器优先：如果熔断了，直接拒绝，不给重试机会
2. 重试优先：没熔断的情况下，先尝试重试
3. 重试仍失败：记录到熔断器，累积到阈值就熔断



### 5.5 与负载均衡的配合（详细讲解）

在前面的实现中，我们的熔断器是**服务级别**的，即对整个服务熔断。但在生产环境中，更常见的做法是**实例级别**的熔断，结合负载均衡实现更精细的流量控制。

#### 5.5.1 为什么需要实例级熔断？

**场景分析：**

假设 `UserService` 有 3 个提供者实例：
```
UserService-instance-1 (192.168.1.10:8080) ✅
UserService-instance-2 (192.168.1.11:8080) ❌ (故障)
UserService-instance-3 (192.168.1.12:8080) ✅
```

**方案对比：**

| 方案 | 服务级熔断 | 实例级熔断 |
|------|----------|----------|
| **粒度** | 粗粒度 | 细粒度 |
| **故障影响** | 一个实例故障导致整个服务不可用 | 仅故障实例被隔离，其他实例继续服务 |
| **资源利用** | 浪费健康实例 | 充分利用健康资源 |
| **恢复速度** | 慢，需要等待整个服务恢复 | 快，故障实例单独恢复 |

**结论：** 实例级熔断更优！

---

#### 5.5.2 实现方案一：基于实例地址的熔断器

**核心思路：**
为每个服务实例维护独立的熔断器，负载均衡时跳过熔断的实例。

**步骤 1：扩展 CircuitBreakerManager**

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreakerManager.java`

```java
package com.rpc.faulttolerance.circuitbreaker;

import com.rpc.faulttolerance.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器管理器（增强版 - 支持实例级熔断）
 */
@Slf4j
public class CircuitBreakerManager {
    
    /** 单例 */
    private static final CircuitBreakerManager INSTANCE = new CircuitBreakerManager();
    
    /** 服务级熔断器缓存：serviceName -> CircuitBreaker */
    private final ConcurrentHashMap<String, CircuitBreaker> serviceCircuitBreakers 
            = new ConcurrentHashMap<>();
    
    /** 实例级熔断器缓存：serviceName#address -> CircuitBreaker */
    private final ConcurrentHashMap<String, CircuitBreaker> instanceCircuitBreakers 
            = new ConcurrentHashMap<>();
    
    /** 默认配置 */
    private float failureRateThreshold = 50.0f;
    private int minNumberOfCalls = 10;
    private long waitDurationInOpenState = 30000;
    private int permittedNumberOfCallsInHalfOpenState = 5;
    
    /** 是否启用实例级熔断 */
    private boolean enableInstanceLevelCircuitBreaker = true;
    
    private CircuitBreakerManager() {}
    
    public static CircuitBreakerManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取服务级熔断器
     */
    public CircuitBreaker getServiceCircuitBreaker(String serviceName) {
        return serviceCircuitBreakers.computeIfAbsent(serviceName, 
                name -> createCircuitBreaker("service:" + name));
    }
    
    /**
     * 获取实例级熔断器
     * @param serviceName 服务名称
     * @param address 实例地址
     * @return 实例熔断器
     */
    public CircuitBreaker getInstanceCircuitBreaker(String serviceName, 
                                                     InetSocketAddress address) {
        String key = buildInstanceKey(serviceName, address);
        return instanceCircuitBreakers.computeIfAbsent(key, 
                k -> createCircuitBreaker("instance:" + key));
    }
    
    /**
     * 构建实例键
     */
    private String buildInstanceKey(String serviceName, InetSocketAddress address) {
        return serviceName + "#" + address.getHostString() + ":" + address.getPort();
    }
    
    /**
     * 创建熔断器
     */
    private CircuitBreaker createCircuitBreaker(String name) {
        log.info("创建熔断器：{}", name);
        return new CircuitBreakerImpl(
                name,
                failureRateThreshold,
                minNumberOfCalls,
                waitDurationInOpenState,
                permittedNumberOfCallsInHalfOpenState
        );
    }
    
    /**
     * 配置全局参数
     */
    public void configure(float failureRateThreshold,
                         int minNumberOfCalls,
                         long waitDurationInOpenState,
                         int permittedNumberOfCallsInHalfOpenState) {
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        
        log.info("熔断器全局配置更新...");
    }
    
    /**
     * 启用/禁用实例级熔断
     */
    public void setEnableInstanceLevelCircuitBreaker(boolean enable) {
        this.enableInstanceLevelCircuitBreaker = enable;
        log.info("实例级熔断已{}", enable ? "启用" : "禁用");
    }
    
    /**
     * 重置指定服务的熔断器
     */
    public void resetServiceCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = serviceCircuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
        }
    }
    
    /**
     * 重置指定实例的熔断器
     */
    public void resetInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
        String key = buildInstanceKey(serviceName, address);
        CircuitBreaker breaker = instanceCircuitBreakers.get(key);
        if (breaker != null) {
            breaker.reset();
        }
    }
    
    /**
     * 打印所有熔断器状态
     */
    public void printStatus() {
        log.info("========== 服务级熔断器状态 ==========");
        serviceCircuitBreakers.forEach((name, breaker) -> {
            log.info("服务：{}, 状态：{}", name, breaker.getState());
        });
        
        log.info("========== 实例级熔断器状态 ==========");
        instanceCircuitBreakers.forEach((key, breaker) -> {
            log.info("实例：{}, 状态：{}", key, breaker.getState());
        });
    }
}
```

**步骤 2：增强 LoadBalancer 接口**

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/loadbalance/LoadBalancer.java`

```java
package com.rpc.loadbalance;

import com.rpc.spi.SPI;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 负载均衡器接口（增强版）
 */
@SPI("random")
public interface LoadBalancer {
    
    /**
     * 从服务列表中选择一个节点
     * @param serviceName 服务名称
     * @param addresses 服务地址列表
     * @return 选中的地址
     */
    InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses);
    
    /**
     * 带熔断检查的选择（新增）
     * @param serviceName 服务名称
     * @param addresses 服务地址列表
     * @param circuitBreakerManager 熔断器管理器
     * @return 选中的地址
     */
    default InetSocketAddress selectWithCircuitBreaker(
            String serviceName, 
            List<InetSocketAddress> addresses,
            CircuitBreakerManager circuitBreakerManager) {
        
        // 默认实现：过滤掉熔断的实例，然后选择
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        // 过滤健康的实例
        List<InetSocketAddress> healthyAddresses = addresses.stream()
            .filter(address -> {
                CircuitBreaker cb = circuitBreakerManager.getInstanceCircuitBreaker(
                        serviceName, address);
                return cb.allowRequest();
            })
            .toList();
        
        if (healthyAddresses.isEmpty()) {
            // 所有实例都熔断了，抛出异常
            throw new CircuitBreakerException(serviceName);
        }
        
        // 从健康实例中选择
        InetSocketAddress selected = select(serviceName, healthyAddresses);
        
        // 记录选择（用于后续失败时记录到对应实例的熔断器）
        recordSelection(serviceName, selected, circuitBreakerManager);
        
        return selected;
    }
    
    /**
     * 记录选择结果（用于熔断器统计）
     */
    default void recordSelection(String serviceName, 
                                InetSocketAddress address,
                                CircuitBreakerManager circuitBreakerManager) {
        // 默认空实现，子类可以重写
    }
    
    /**
     * 获取负载均衡策略名称
     */
    String getName();
}
```

**步骤 3：修改 RpcNettyClient 整合逻辑**

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/transport/netty/client/RpcNettyClient.java`

```java
package com.rpc.transport.netty.client;

// ... 保留原有导入 ...
import com.rpc.faulttolerance.circuitbreaker.CircuitBreaker;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.faulttolerance.retry.DefaultRetryStrategy;
import com.rpc.faulttolerance.retry.RetryExecutor;
import com.rpc.loadbalance.LoadBalancer;
// ... 其他导入保持不变 ...

/**
 * RPC Netty 客户端（增强版 - 带实例级熔断）
 */
@Slf4j
public class RpcNettyClient {
    // ... 保留原有字段 ...
    
    private final CircuitBreakerManager circuitBreakerManager;
    private final RetryExecutor retryExecutor;
    private final LoadBalancer loadBalancer;
    private final ServiceRegistry serviceRegistry;
    // ... 其他字段保持不变 ...
    
    /**
     * 带服务注册中心的构造方法
     */
    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.loadBalancer = config.getLoadBalancer();
        
        // 初始化容错组件
        this.circuitBreakerManager = CircuitBreakerManager.getInstance();
        this.retryExecutor = new RetryExecutor(
                new DefaultRetryStrategy(), 
                config.getRetryTimes()
        );
        
        // ... 保留原有 Bootstrap 配置 ...
    }
    
    /**
     * 发送 RPC 请求（增强版 - 实例级熔断）
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        String serviceName = rpcRequest.getServiceName();
        
        try {
            // 使用重试执行器，内部会调用 doSendRequestWithInstanceCircuitBreaker
            return retryExecutor.executeWithRetry(rpcRequest, 
                () -> doSendRequestWithInstanceCircuitBreaker(rpcRequest));
                
        } catch (RpcException e) {
            // 重试失败，记录到服务级熔断器
            CircuitBreaker serviceCb = circuitBreakerManager.getServiceCircuitBreaker(serviceName);
            serviceCb.recordFailure();
            throw e;
        } catch (Exception e) {
            CircuitBreaker serviceCb = circuitBreakerManager.getServiceCircuitBreaker(serviceName);
            serviceCb.recordFailure();
            throw new RpcException(ErrorCode.SERVER_ERROR, 
                    "RPC 调用失败：" + e.getMessage(), e);
        }
    }
    
    /**
     * 发送请求（带实例级熔断检查）
     */
    private RpcResponse doSendRequestWithInstanceCircuitBreaker(RpcRequest rpcRequest) 
            throws Exception {
        String serviceName = rpcRequest.getServiceName();
        
        // 1. 生成请求 ID
        long requestId = generateRequestId();
        rpcRequest.setRequestId(String.valueOf(requestId));
        
        // 2. 创建 Future 用于接收响应
        CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);
        
        // 3. 从注册中心获取服务提供者列表
        List<InetSocketAddress> addresses = serviceRegistry.lookup(serviceName);
        
        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(ErrorCode.SERVICE_NOT_FOUND, 
                    "服务未找到：" + serviceName);
        }
        
        // 4. 【关键】使用带熔断检查的负载均衡选择实例
        InetSocketAddress selectedAddress;
        try {
            selectedAddress = loadBalancer.selectWithCircuitBreaker(
                    serviceName, addresses, circuitBreakerManager);
        } catch (CircuitBreakerException e) {
            // 所有实例都熔断了
            log.error("所有服务实例都已熔断：{}", serviceName);
            throw e;
        }
        
        String host = selectedAddress.getAddress().getHostAddress();
        int port = selectedAddress.getPort();
        log.info("选择服务实例：{} -> {}", serviceName, selectedAddress);
        
        // 5. 获取连接
        RpcConnection connection = connectionPool.getConnection(host, port);
        
        // 6. 构建请求消息
        RpcHeader header = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType((byte) SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                .messageType(RpcMessageType.REQUEST.getCode())
                .reserved((byte) 0)
                .requestId(requestId)
                .build();
        
        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(rpcRequest);
        
        // 7. 发送消息
        connection.getChannel().writeAndFlush(message).sync();
        log.debug("请求已发送：{}.{}", rpcRequest.getServiceName(),
                rpcRequest.getMethodName());
        
        // 8. 同步等待响应（带超时）
        RpcResponse response = future.get(readTimeout, TimeUnit.MILLISECONDS);
        
        // 9. 检查响应状态
        if (response.getCode() != 200) {
            throw new RpcException(ErrorCode.SERVICE_EXCEPTION, 
                    "RPC 调用失败：" + response.getMessage());
        }
        
        // 10. 【关键】记录成功到实例级熔断器
        CircuitBreaker instanceCb = circuitBreakerManager.getInstanceCircuitBreaker(
                serviceName, selectedAddress);
        instanceCb.recordSuccess();
        
        return response;
    }
    
    // ... 保留原有 close 方法和其他方法 ...
}
```

---

#### 5.5.3 实现方案二：在负载均衡器内部集成熔断

**核心思路：**
将熔断逻辑直接嵌入到负载均衡器的选择过程中，对使用者透明。

**示例：带熔断的随机负载均衡器**

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/loadbalance/impl/CircuitBreakerRandomLoadBalancer.java`

```java
package com.rpc.loadbalance.impl;

import com.rpc.faulttolerance.circuitbreaker.CircuitBreaker;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 带熔断机制的随机负载均衡器
 */
@Slf4j
public class CircuitBreakerRandomLoadBalancer implements LoadBalancer {
    
    private final Random random = new Random();
    private final CircuitBreakerManager circuitBreakerManager;
    
    public CircuitBreakerRandomLoadBalancer() {
        this.circuitBreakerManager = CircuitBreakerManager.getInstance();
    }
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        // 步骤 1：过滤掉已熔断的实例
        List<InetSocketAddress> healthyAddresses = new ArrayList<>();
        for (InetSocketAddress address : addresses) {
            CircuitBreaker cb = circuitBreakerManager.getInstanceCircuitBreaker(
                    serviceName, address);
            
            if (cb.allowRequest()) {
                healthyAddresses.add(address);
            } else {
                log.debug("实例已熔断，跳过：{}", address);
            }
        }
        
        // 步骤 2：检查是否有健康实例
        if (healthyAddresses.isEmpty()) {
            log.error("所有实例都已熔断：{}, 原始地址列表：{}", serviceName, addresses);
            throw new CircuitBreakerException(serviceName);
        }
        
        // 步骤 3：从健康实例中随机选择
        int index = random.nextInt(healthyAddresses.size());
        InetSocketAddress selected = healthyAddresses.get(index);
        
        log.info("[CircuitBreaker-Random] 选择：{} (共{}/{}健康)", 
                selected, healthyAddresses.size(), addresses.size());
        
        return selected;
    }
    
    @Override
    public String getName() {
        return "circuitbreaker-random";
    }
    
    /**
     * 记录选择结果（供外部调用，用于熔断器统计）
     */
    public void recordResult(String serviceName, 
                            InetSocketAddress address, 
                            boolean success) {
        if (address != null) {
            CircuitBreaker cb = circuitBreakerManager.getInstanceCircuitBreaker(
                    serviceName, address);
            
            if (success) {
                cb.recordSuccess();
            } else {
                cb.recordFailure();
            }
        }
    }
}
```

**SPI 配置：**

📁 **文件位置**: `rpc-core/src/main/resources/META-INF/rpc/com.rpc.loadbalance.LoadBalancer`

```properties
random=com.rpc.loadbalance.impl.RandomLoadBalancer
roundrobin=com.rpc.loadbalance.impl.RoundRobinLoadBalancer
consistenthash=com.rpc.loadbalance.impl.ConsistentHashLoadBalancer
leastconnections=com.rpc.loadbalance.impl.LeastConnectionsLoadBalancer
circuitbreaker-random=com.rpc.loadbalance.impl.CircuitBreakerRandomLoadBalancer
default=consistenthash
```

---

#### 5.5.4 完整调用链路时序图

```
┌─────────┐      ┌──────────────┐      ┌─────────────┐      ┌──────────┐      ┌──────────┐
│ 客户端  │      │ RpcNettyClient│      │ LoadBalancer│      │CircuitBreaker│    │ 服务端   │
│         │      │              │      │             │      │  Manager  │      │          │
└────┬────┘      └──────┬───────┘      └──────┬──────┘      └────┬─────┘      └────┬─────┘
     │                  │                     │                  │                 │
     │ ①sendRequest()   │                     │                  │                 │
     │─────────────────>│                     │                  │                 │
     │                  │                     │                  │                 │
     │                  │ ②lookup()           │                  │                 │
     │                  │────────────────────>│                  │                 │
     │                  │                     │                  │                 │
     │                  │ ③addresses[]        │                  │                 │
     │                  │<────────────────────│                  │                 │
     │                  │                     │                  │                 │
     │                  │ ④selectWithCircuitBreaker()           │                 │
     │                  │────────────────────>│                  │                 │
     │                  │                     │                  │                 │
     │                  │                     │ ⑤allowRequest()  │                 │
     │                  │                     │─────────────────>│                 │
     │                  │                     │                  │                 │
     │                  │                     │ ⑥true (未熔断)   │                 │
     │                  │                     │<─────────────────│                 │
     │                  │                     │                  │                 │
     │                  │ ⑦selectedAddress    │                  │                 │
     │                  │<────────────────────│                  │                 │
     │                  │                     │                  │                 │
     │                  │ ⑧getConnection()    │                  │                 │
     │                  │─────────────────────────────────────────────────────────>│
     │                  │                     │                  │                 │
     │                  │ ⑨writeAndFlush()    │                  │                 │
     │                  │─────────────────────────────────────────────────────────>│
     │                  │                     │                  │                 │
     │                  │ ⑩response           │                  │                 │
     │                  │<─────────────────────────────────────────────────────────│
     │                  │                     │                  │                 │
     │                  │ ⑪recordSuccess()    │                  │                 │
     │                  │─────────────────────────────────────────────────────────>│
     │                  │                     │                  │                 │
     │ Response         │                     │                  │                 │
     │<─────────────────│                     │                  │                 │
     │                  │                     │                  │                 │
```

---

#### 5.5.5 实战演练：模拟实例故障场景

**测试代码：**

📁 **文件位置**: `rpc-core/src/test/java/com/rpc/faulttolerance/CircuitBreakerLoadBalanceTest.java`

```java
package com.rpc.faulttolerance;

import com.rpc.common.CircuitBreakerException;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreaker;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerState;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.loadbalance.impl.CircuitBreakerRandomLoadBalancer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断器与负载均衡配合测试
 */
@Slf4j
class CircuitBreakerLoadBalanceTest {
    
    private CircuitBreakerManager circuitBreakerManager;
    private LoadBalancer loadBalancer;
    private List<InetSocketAddress> addresses;
    
    @BeforeEach
    void setUp() {
        circuitBreakerManager = CircuitBreakerManager.getInstance();
        loadBalancer = new CircuitBreakerRandomLoadBalancer();
        
        // 准备 3 个实例
        addresses = Arrays.asList(
            new InetSocketAddress("192.168.1.10", 8080),
            new InetSocketAddress("192.168.1.11", 8080),
            new InetSocketAddress("192.168.1.12", 8080)
        );
    }
    
    @Test
    void testSelectHealthyInstances() {
        log.info("===== 测试 1：正常选择健康实例 =====");
        
        // 连续选择 10 次，都应该成功
        for (int i = 0; i < 10; i++) {
            InetSocketAddress selected = loadBalancer.select("testService", addresses);
            assertNotNull(selected);
            assertTrue(addresses.contains(selected));
            log.info("第{}次选择：{}", i + 1, selected);
        }
    }
    
    @Test
    void testSkipCircuitedInstance() {
        log.info("===== 测试 2：跳过已熔断的实例 =====");
        
        // 手动让实例 2 熔断
        CircuitBreaker cb2 = circuitBreakerManager.getInstanceCircuitBreaker(
                "testService", addresses.get(1));
        
        for (int i = 0; i < 20; i++) {
            cb2.recordFailure();
        }
        
        log.info("实例 2 状态：{}", cb2.getState());
        assertEquals(CircuitBreakerState.OPEN, cb2.getState());
        
        // 现在选择应该只会在实例 1 和 3 之间
        for (int i = 0; i < 10; i++) {
            InetSocketAddress selected = loadBalancer.select("testService", addresses);
            assertNotNull(selected);
            assertNotEquals(addresses.get(1), selected); // 不应该是实例 2
            log.info("第{}次选择：{}", i + 1, selected);
        }
    }
    
    @Test
    void testAllInstancesCircuited() {
        log.info("===== 测试 3：所有实例熔断 =====");
        
        // 让所有实例熔断
        for (InetSocketAddress address : addresses) {
            CircuitBreaker cb = circuitBreakerManager.getInstanceCircuitBreaker(
                    "testService", address);
            for (int i = 0; i < 20; i++) {
                cb.recordFailure();
            }
        }
        
        // 此时选择应该抛出 CircuitBreakerException
        assertThrows(CircuitBreakerException.class, () -> {
            loadBalancer.select("testService", addresses);
        });
        
        log.info("所有实例熔断，抛出异常符合预期 ✓");
    }
    
    @Test
    void testInstanceRecovery() throws InterruptedException {
        log.info("===== 测试 4：实例恢复 =====");
        
        // 让实例 2 熔断
        CircuitBreaker cb2 = circuitBreakerManager.getInstanceCircuitBreaker(
                "testService", addresses.get(1));
        
        for (int i = 0; i < 20; i++) {
            cb2.recordFailure();
        }
        
        log.info("实例 2 熔断，状态：{}", cb2.getState());
        
        // 等待熔断器进入 HALF_OPEN（配置的是 30 秒，这里为了测试改为 1 秒）
        Thread.sleep(1100);
        
        // 模拟探测请求成功
        cb2.recordSuccess();
        
        log.info("实例 2 恢复，状态：{}", cb2.getState());
        assertEquals(CircuitBreakerState.CLOSED, cb2.getState());
        
        // 现在应该又能选择实例 2 了
        boolean selectedInstance2 = false;
        for (int i = 0; i < 100; i++) {
            InetSocketAddress selected = loadBalancer.select("testService", addresses);
            if (selected.equals(addresses.get(1))) {
                selectedInstance2 = true;
                break;
            }
        }
        
        assertTrue(selectedInstance2, "应该能选择到恢复的实例 2");
        log.info("实例 2 恢复后重新被选择 ✓");
    }
}
```

---

#### 5.5.6 两种方案对比

| 特性 | 方案一：管理器集成 | 方案二：负载均衡器内置 |
|------|------------------|---------------------|
| **耦合度** | 低，职责分离 | 高，熔断逻辑嵌入负载均衡 |
| **灵活性** | 高，可独立替换 | 中，需为每种策略实现熔断版本 |
| **透明度** | 中，需显式调用 | 高，对使用者透明 |
| **实现复杂度** | 中等 | 较高（需为每种策略实现） |
| **推荐场景** | 教学、学习原理 | 生产环境、追求简洁 |

**建议：**
- **学习阶段**：使用方案一，能清晰理解熔断器和负载均衡如何协作
- **生产环境**：可以考虑方案二的优化版本，或者使用成熟的框架如 Sentinel

---

#### 5.5.7 生产环境最佳实践

1. **多级熔断策略**
   ```java
   // 第一层：实例级熔断
   CircuitBreaker instanceCb = circuitBreakerManager.getInstanceCircuitBreaker(...);
   
   // 第二层：服务级熔断（兜底）
   CircuitBreaker serviceCb = circuitBreakerManager.getServiceCircuitBreaker(...);
   
   // 第三层：全局熔断（雪崩保护）
   CircuitBreaker globalCb = circuitBreakerManager.getGlobalCircuitBreaker();
   ```

2. **动态调整熔断参数**
   ```java
   // 根据时间段调整
   if (isPeakHours()) {
       circuitBreakerManager.configure(60.0f, 20, 60000, 10);
   } else {
       circuitBreakerManager.configure(50.0f, 10, 30000, 5);
   }
   ```

3. **监控告警**
   ```java
   // 定时检查熔断器状态
   scheduler.scheduleAtFixedRate(() -> {
       Map<String, Long> circuitedCount = getCircuitedInstanceCount();
       if (circuitedCount > threshold) {
           sendAlert("大量实例熔断！");
       }
   }, 0, 10, TimeUnit.SECONDS);
   ```

---

**本节小结：**

✅ 理解了实例级熔断的必要性和优势  
✅ 掌握了两种熔断器与负载均衡的配合方案  
✅ 学会了如何编写集成测试验证功能  
✅ 了解了生产环境的最佳实践  

**下一步：** 动手实现其中一种方案，并运行测试用例验证效果！

---

## 六、熔断器实现

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreaker.java`

```java
package com.rpc.faulttolerance.circuitbreaker;

/**
 * 熔断器接口
 * 定义熔断器的基本行为
 */
public interface CircuitBreaker {
    
    /**
     * 判断是否允许请求通过
     * @return true-允许，false-拒绝
     */
    boolean allowRequest();
    
    /**
     * 记录成功调用
     */
    void recordSuccess();
    
    /**
     * 记录失败调用
     */
    void recordFailure();
    
    /**
     * 获取当前状态
     * @return 熔断器状态
     */
    CircuitBreakerState getState();
    
    /**
     * 手动重置熔断器
     */
    void reset();
}
```

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreakerState.java`

```java
package com.rpc.faulttolerance.circuitbreaker;

/**
 * 熔断器状态枚举
 */
public enum CircuitBreakerState {
    /** 关闭状态 - 正常 */
    CLOSED,
    
    /** 打开状态 - 熔断 */
    OPEN,
    
    /** 半开状态 - 探测 */
    HALF_OPEN
}
```

### 4.3 熔断器实现

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreakerImpl.java`

```java
package com.rpc.faulttolerance.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 熔断器实现
 * 基于滑动窗口统计失败率
 */
@Slf4j
public class CircuitBreakerImpl implements CircuitBreaker {
    
    /** 服务名称 */
    private final String serviceName;
    
    /** 失败率阈值（百分比） */
    private final float failureRateThreshold;
    
    /** 最小请求数（达到此数量才开始统计） */
    private final int minNumberOfCalls;
    
    /** 熔断器打开后的休眠时间（毫秒） */
    private final long waitDurationInOpenState;
    
    /** 半开状态允许的最大请求数 */
    private final int permittedNumberOfCallsInHalfOpenState;
    
    // ========== 统计数据 ==========
    
    /** 总请求数（滑动窗口） */
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    
    /** 失败请求数（滑动窗口） */
    private final AtomicInteger failedCalls = new AtomicInteger(0);
    
    /** 熔断器状态 */
    private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
    
    /** 熔断器打开的时间戳 */
    private volatile long lastFailureTime = 0;
    
    /** 半开状态已通过的请求数 */
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
    
    public CircuitBreakerImpl(String serviceName,
                              float failureRateThreshold,
                              int minNumberOfCalls,
                              long waitDurationInOpenState,
                              int permittedNumberOfCallsInHalfOpenState) {
        this.serviceName = serviceName;
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
    }
    
    @Override
    public boolean allowRequest() {
        CircuitBreakerState currentState = getState();
        
        if (currentState == CircuitBreakerState.OPEN) {
            // 检查是否可以进入半开状态
            long elapsed = System.currentTimeMillis() - lastFailureTime;
            if (elapsed >= waitDurationInOpenState) {
                log.info("熔断器从 OPEN 进入 HALF_OPEN: {}", serviceName);
                state = CircuitBreakerState.HALF_OPEN;
                halfOpenCalls.set(0);
                return true;
            }
            return false;
        }
        
        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 半开状态限制请求数
            int currentCalls = halfOpenCalls.incrementAndGet();
            if (currentCalls <= permittedNumberOfCallsInHalfOpenState) {
                return true;
            }
            log.debug("半开状态请求数超限，拒绝：{}", serviceName);
            return false;
        }
        
        // CLOSED 状态允许所有请求
        return true;
    }
    
    @Override
    public void recordSuccess() {
        totalCalls.incrementAndGet();
        
        CircuitBreakerState currentState = getState();
        
        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 半开状态下成功，进入关闭状态
            log.info("熔断器从 HALF_OPEN 进入 CLOSED: {}", serviceName);
            state = CircuitBreakerState.CLOSED;
            resetStatistics();
        }
    }
    
    @Override
    public void recordFailure() {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();
        
        CircuitBreakerState currentState = getState();
        
        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 半开状态下失败，重新打开
            log.warn("熔断器从 HALF_OPEN 重新进入 OPEN: {}", serviceName);
            state = CircuitBreakerState.OPEN;
        } else if (currentState == CircuitBreakerState.CLOSED) {
            // 关闭状态下检查是否达到阈值
            checkAndUpdateState();
        }
    }
    
    @Override
    public CircuitBreakerState getState() {
        if (state == CircuitBreakerState.OPEN) {
            // 检查是否可以进入半开状态
            long elapsed = System.currentTimeMillis() - lastFailureTime;
            if (elapsed >= waitDurationInOpenState) {
                state = CircuitBreakerState.HALF_OPEN;
                halfOpenCalls.set(0);
            }
        }
        return state;
    }
    
    @Override
    public void reset() {
        state = CircuitBreakerState.CLOSED;
        resetStatistics();
        log.info("熔断器已重置：{}", serviceName);
    }
    
    /**
     * 检查并更新熔断器状态
     */
    private void checkAndUpdateState() {
        int total = totalCalls.get();
        int failed = failedCalls.get();
        
        // 未达到最小请求数，不统计
        if (total < minNumberOfCalls) {
            return;
        }
        
        // 计算失败率
        float failureRate = (float) failed / total * 100;
        
        log.debug("熔断器统计：service={}, total={}, failed={}, failureRate={}%", 
                serviceName, total, failed, failureRate);
        
        // 超过阈值，打开熔断器
        if (failureRate >= failureRateThreshold) {
            log.warn("失败率超阈值，熔断器打开：{} (失败率={}%, 阈值={}%)", 
                    serviceName, failureRate, failureRateThreshold);
            state = CircuitBreakerState.OPEN;
            resetStatistics();
        }
    }
    
    /**
     * 重置统计数据
     */
    private void resetStatistics() {
        totalCalls.set(0);
        failedCalls.set(0);
        halfOpenCalls.set(0);
    }
}
```

### 4.4 熔断器管理器

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreakerManager.java`

```java
package com.rpc.faulttolerance.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器管理器
 * 为每个服务维护独立的熔断器
 */
@Slf4j
public class CircuitBreakerManager {
    
    /** 单例 */
    private static final CircuitBreakerManager INSTANCE = new CircuitBreakerManager();
    
    /** 服务熔断器缓存 */
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    
    /** 默认配置 */
    private float failureRateThreshold = 50.0f;  // 失败率 50%
    private int minNumberOfCalls = 10;            // 最小请求数
    private long waitDurationInOpenState = 30000; // 休眠 30 秒
    private int permittedNumberOfCallsInHalfOpenState = 5; // 半开允许 5 个请求
    
    private CircuitBreakerManager() {}
    
    public static CircuitBreakerManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取服务的熔断器
     */
    public CircuitBreaker getCircuitBreaker(String serviceName) {
        return circuitBreakers.computeIfAbsent(serviceName, 
                name -> createCircuitBreaker(name));
    }
    
    /**
     * 创建熔断器
     */
    private CircuitBreaker createCircuitBreaker(String serviceName) {
        log.info("为服务创建熔断器：{}", serviceName);
        return new CircuitBreakerImpl(
                serviceName,
                failureRateThreshold,
                minNumberOfCalls,
                waitDurationInOpenState,
                permittedNumberOfCallsInHalfOpenState
        );
    }
    
    /**
     * 配置全局参数
     */
    public void configure(float failureRateThreshold,
                         int minNumberOfCalls,
                         long waitDurationInOpenState,
                         int permittedNumberOfCallsInHalfOpenState) {
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        
        log.info("熔断器全局配置更新：failureRateThreshold={}, minNumberOfCalls={}, " +
                        "waitDurationInOpenState={}, permittedNumberOfCallsInHalfOpenState={}",
                failureRateThreshold, minNumberOfCalls, waitDurationInOpenState,
                permittedNumberOfCallsInHalfOpenState);
    }
    
    /**
     * 重置指定服务的熔断器
     */
    public void resetCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = circuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
        }
    }
    
    /**
     * 打印所有熔断器状态
     */
    public void printStatus() {
        circuitBreakers.forEach((name, breaker) -> {
            log.info("熔断器状态：service={}, state={}", name, breaker.getState());
        });
    }
}
```

---

## 五、整合到 RPC 客户端

### 6.2 完整调用链路分析

让我们追踪一次完整的 RPC 调用，看看熔断器如何工作：

```
调用链路时序图：

客户端线程                          CircuitBreaker                    服务端
    │                                    │                                │
    │───① allowRequest()? ─────────────>│                                │
    │    (检查是否熔断)                   │                                │
    │<───true (CLOSED 状态) ────────────│                                │
    │                                    │                                │
    │───② doSendRequest() ─────────────>│                                │
    │    (发送网络请求)                   │                                │
    │                                    │                                │───处理请求───>
    │                                    │                                │
    │<───③ 响应/异常────────────────────│                                │
    │                                    │                                │
    │───④ recordSuccess()/Failure() ───>│                                │
    │    (更新统计)                       │                                │
    │                                    │                                │
    │                                    │───检查阈值 ───────────────────>│
    │                                    │───可能触发熔断 ───────────────>│
```

**关键代码段：**

```java
// RpcNettyClient.java 第 89-132 行
public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
    String serviceName = rpcRequest.getServiceName();
    
    // ========== 步骤 1: 熔断检查 ==========
    CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(serviceName);
    if (!circuitBreaker.allowRequest()) {
        log.warn("熔断器已打开，拒绝请求：{}", serviceName);
        throw new CircuitBreakerException(serviceName);  // 快速失败
    }
    
    try {
        // ========== 步骤 2: 重试执行 ==========
        return retryExecutor.executeWithRetry(rpcRequest, 
            () -> doSendRequest(rpcRequest, circuitBreaker));
            
    } catch (RpcException e) {
        // ========== 步骤 3: 记录失败 ==========
        circuitBreaker.recordFailure();
        throw e;
    } catch (Exception e) {
        // ========== 步骤 4: 包装异常并记录 ==========
        circuitBreaker.recordFailure();
        throw new RpcException(ErrorCode.SERVER_ERROR,
                "RPC 调用失败：" + e.getMessage(), e);
    }
}
```

### 6.3 实际运行示例

**场景：下游服务逐渐恢复的过程**

```java
// 配置
CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
manager.configure(
    50.0f,      // 50% 失败率触发熔断
    10,         // 最少 10 个请求开始统计
    30000,      // 熔断后休眠 30 秒
    5           // 半开允许 5 个请求
);

// 模拟调用过程
for (int i = 0; i < 100; i++) {
    try {
        String result = helloService.sayHello("User" + i);
        System.out.println("✓ 成功：" + result);
    } catch (CircuitBreakerException e) {
        System.err.println("✗ 熔断：" + e.getMessage());
    } catch (RpcException e) {
        System.err.println("✗ RPC 异常：" + e.getMessage());
    }
}

// 输出示例：
// i=0-9:   ✓ 成功 (正常)
// i=10-19: ✗ RPC 异常 (服务开始失败，但未满 10 个，不熔断)
// i=20-29: ✗ RPC 异常 (失败率达 60%，触发熔断!)
// i=30-39: ✗ 熔断 (直接拒绝，不发送请求)
// ...等待 30 秒...
// i=40-44: ✓ 成功 (半开状态，探测成功)
// i=45+:   ✓ 成功 (恢复 CLOSED，正常)
```

## 六、整合到 RPC 客户端

### 6.1 增强 RpcNettyClient

```java
package com.rpc.transport.netty.client;

import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.common.CircuitBreakerException;
import com.rpc.common.RpcException;
import com.rpc.config.RpcClientConfig;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreaker;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerState;
import com.rpc.faulttolerance.retry.DefaultRetryStrategy;
import com.rpc.faulttolerance.retry.RetryExecutor;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.protocol.*;
import com.rpc.registry.ServiceRegistry;
import com.rpc.serialize.factory.SerializerFactory;
import com.rpc.transport.netty.client.connection.RpcConnection;
import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.transport.netty.client.handler.RpcClientHandler;
import com.rpc.transport.netty.client.handler.heart.HeartbeatHandler;
import com.rpc.transport.netty.client.handler.heart.ReconnectHandler;
import com.rpc.transport.netty.client.manager.RequestManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 客户端（增强版 - 带容错机制）
 */
@Slf4j
public class RpcNettyClient {
    // ... 保留原有字段 ...
    
    // ========== 新增容错相关字段 ==========
    
    /** 熔断器管理器 */
    private final CircuitBreakerManager circuitBreakerManager;
    
    /** 重试执行器 */
    private final RetryExecutor retryExecutor;
    
    // ... 保留原有构造方法 ...
    
    /**
     * 带服务注册中心的构造方法
     */
    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.loadBalancer = config.getLoadBalancer();
        
        // ========== 初始化容错组件 ==========
        this.circuitBreakerManager = CircuitBreakerManager.getInstance();
        this.retryExecutor = new RetryExecutor(
                new DefaultRetryStrategy(), 
                config.getRetryTimes()
        );
        
        // ... 保留原有 Bootstrap 配置 ...
        // （此处省略 Bootstrap 配置代码，保持原样）
    }
    
    /**
     * 发送 RPC 请求（增强版 - 带重试和熔断）
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception {
        String serviceName = rpcRequest.getServiceName();
        
        // 1. 检查熔断器状态
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(serviceName);
        if (!circuitBreaker.allowRequest()) {
            log.warn("熔断器已打开，拒绝请求：{}", serviceName);
            throw new CircuitBreakerException(serviceName);
        }
        
        try {
            // 2. 使用重试执行器发送请求
            return retryExecutor.executeWithRetry(rpcRequest, 
                () -> doSendRequest(rpcRequest, circuitBreaker));
                
        } catch (RpcException e) {
            // 记录失败到熔断器
            circuitBreaker.recordFailure();
            throw e;
        } catch (Exception e) {
            // 包装为 RpcException
            circuitBreaker.recordFailure();
            throw new RpcException(ErrorCode.SERVER_ERROR, 
                    "RPC 调用失败：" + e.getMessage(), e);
        }
    }
    
    /**
     * 实际发送请求的逻辑（从原 sendRequest 方法迁移过来）
     */
    private RpcResponse doSendRequest(RpcRequest rpcRequest, 
                                      CircuitBreaker circuitBreaker) throws Exception {
        try {
            // 1. 生成请求 ID
            long requestId = generateRequestId();
            rpcRequest.setRequestId(String.valueOf(requestId));
            
            // 2. 创建 Future 用于接收响应
            CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);
            
            // 3. 从注册中心获取服务提供者列表
            List<InetSocketAddress> addresses = serviceRegistry.lookup(rpcRequest.getServiceName());
            // 4. 通过负载均衡获取地址
            InetSocketAddress address = loadBalancer.select(rpcRequest.getServiceName(), addresses);
            String host = address.getAddress().getHostAddress();
            int port = address.getPort();
            log.info("服务发现选择地址：{}", address);
            
            // 5. 获取连接
            RpcConnection connection = connectionPool.getConnection(host, port);
            
            // 6. 构建请求消息
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                    .messageType(RpcMessageType.REQUEST.getCode())
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();
            
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(rpcRequest);
            
            // 7. 发送消息
            connection.getChannel().writeAndFlush(message).sync();
            log.debug("请求已发送：{}.{}", rpcRequest.getServiceName(),
                    rpcRequest.getMethodName());
            
            // 8. 同步等待响应（带超时）
            RpcResponse response = future.get(readTimeout, TimeUnit.MILLISECONDS);
            
            // 9. 检查响应状态
            if (response.getCode() != 200) {
                throw new RpcException(ErrorCode.SERVICE_EXCEPTION, 
                        "RPC 调用失败：" + response.getMessage());
            }
            
            // 10. 记录成功
            circuitBreaker.recordSuccess();
            
            return response;
            
        } catch (Exception e) {
            log.error("发送请求失败", e);
            requestManager.failRequest(Long.parseLong(rpcRequest.getRequestId()), e);
            throw e;
        }
    }
    
    // ... 保留原有 close 方法和其他方法 ...
}
```

---

## 六、降级策略

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/degrade/DegradationPolicy.java`

```java
package com.rpc.faulttolerance.degrade;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;

/**
 * 降级策略接口
 * 当服务不可用时提供兜底方案
 */
public interface DegradationPolicy {
    
    /**
     * 执行降级逻辑
     * @param request 原始请求
     * @param cause 导致降级的原因
     * @return 降级响应
     */
    RpcResponse degrade(RpcRequest request, Throwable cause);
}
```

### 6.2 快速失败降级

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/degrade/FailFastDegradation.java`

```java
package com.rpc.faulttolerance.degrade;

import com.rpc.common.ErrorCode;
import com.rpc.common.RpcException;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 快速失败降级策略
 * 立即返回错误，不等待
 */
@Slf4j
public class FailFastDegradation implements DegradationPolicy {
    
    @Override
    public RpcResponse degrade(RpcRequest request, Throwable cause) {
        log.warn("执行快速失败降级：{}.{}", 
                request.getServiceName(), request.getMethodName());
        
        return RpcResponse.fail(
                ErrorCode.SERVICE_DEGRADED.getCode(),
                "服务已降级：" + cause.getMessage(),
                request.getRequestId()
        );
    }
}
```

### 6.3 默认值降级

📁 **文件位置**: `rpc-core/src/main/java/com/rpc/faulttolerance/degrade/DefaultValueDegradation.java`

```java
package com.rpc.faulttolerance.degrade;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认值降级策略
 * 返回预设的默认值
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultValueDegradation implements DegradationPolicy {
    
    /** 服务的默认返回值映射 */
    private final Map<String, Object> defaultValues = new ConcurrentHashMap<>();
    
    /**
     * 设置服务的默认返回值
     */
    public void setDefaultValue(String serviceMethod, Object value) {
        defaultValues.put(serviceMethod, value);
        log.info("设置默认值：{} = {}", serviceMethod, value);
    }
    
    @Override
    public RpcResponse degrade(RpcRequest request, Throwable cause) {
        String key = request.getServiceName() + "#" + request.getMethodName();
        Object defaultValue = defaultValues.get(key);
        
        if (defaultValue != null) {
            log.info("使用默认值降级：{} = {}", key, defaultValue);
            return RpcResponse.success(defaultValue, request.getRequestId());
        }
        
        log.warn("无默认值，使用快速失败：{}", key);
        return RpcResponse.fail(503, "服务降级且无默认值", request.getRequestId());
    }
}
```

---

## 七、完整使用示例

```java
// 在 RpcClientConfig 中配置
RpcClientConfig config = RpcClientConfig.custom()
        .retryTimes(3)              // 最多重试 3 次
        .connectTimeout(5000)       // 连接超时 5 秒
        .readTimeout(10000)         // 读取超时 10 秒
        .build();

// 配置熔断器参数
CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
manager.configure(
        50.0f,      // 失败率 50% 触发熔断
        10,         // 最少 10 个请求开始统计
        30000,      // 熔断后休眠 30 秒
        5           // 半开状态允许 5 个请求
);
```

### 7.2 消费者端使用

```java
package com.rpc.example;

import com.rpc.common.CircuitBreakerException;
import com.rpc.common.RpcException;
import com.rpc.proxy.impl.RpcProxyFactory;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.registry.impl.zookeeper.ZooKeeperServiceRegistry;

public class ExampleConsumerApplication {
    
    public static void main(String[] args) {
        try {
            // 1. 创建服务注册中心
            ZooKeeperServiceRegistry registry = new ZooKeeperServiceRegistry("127.0.0.1:2181");
            
            // 2. 创建 RPC 客户端（带容错）
            RpcNettyClient client = new RpcNettyClient(config, registry);
            
            // 3. 创建代理
            HelloService helloService = RpcProxyFactory.createProxy(
                    HelloService.class, client);
            
            // 4. 调用服务（自动重试）
            for (int i = 0; i < 10; i++) {
                try {
                    String result = helloService.sayHello("User" + i);
                    System.out.println("结果：" + result);
                } catch (CircuitBreakerException e) {
                    // 熔断器打开，快速失败
                    System.err.println("服务熔断：" + e.getMessage());
                } catch (RpcException e) {
                    // RPC 异常（已重试多次）
                    System.err.println("RPC 异常：" + e.getMessage());
                }
                
                Thread.sleep(1000);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

---

## 八、单元测试

## 九、性能优化建议

📁 **文件位置**: `rpc-core/src/test/java/com/rpc/faulttolerance/RetryStrategyTest.java`

```java
package com.rpc.faulttolerance;

import com.rpc.common.ErrorCode;
import com.rpc.common.RpcException;
import com.rpc.faulttolerance.retry.DefaultRetryStrategy;
import com.rpc.faulttolerance.retry.RetryStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重试策略测试
 */
class RetryStrategyTest {
    
    private RetryStrategy retryStrategy;
    
    @BeforeEach
    void setUp() {
        retryStrategy = new DefaultRetryStrategy();
    }
    
    @Test
    void testShouldRetry_NetworkException() {
        RpcException exception = new RpcException(
                ErrorCode.NETWORK_TIMEOUT, "连接超时");
        
        assertTrue(retryStrategy.shouldRetry(exception, 0, 3));
        assertTrue(retryStrategy.shouldRetry(exception, 1, 3));
        assertTrue(retryStrategy.shouldRetry(exception, 2, 3));
        assertFalse(retryStrategy.shouldRetry(exception, 3, 3)); // 超限
    }
    
    @Test
    void testShouldRetry_BusinessException() {
        RpcException exception = new RpcException(
                ErrorCode.ILLEGAL_ARGUMENT, "参数错误");
        
        // 业务异常不可重试
        assertFalse(retryStrategy.shouldRetry(exception, 0, 3));
    }
    
    @Test
    void testGetDelay_ExponentialBackoff() {
        long delay0 = retryStrategy.getDelay(0);
        long delay1 = retryStrategy.getDelay(1);
        long delay2 = retryStrategy.getDelay(2);
        
        // 验证指数增长
        assertTrue(delay1 > delay0);
        assertTrue(delay2 > delay1);
        
        // 验证有随机抖动（不精确相等）
        System.out.println("Delay 0: " + delay0);
        System.out.println("Delay 1: " + delay1);
        System.out.println("Delay 2: " + delay2);
    }
}
```

### 8.2 熔断器测试

📁 **文件位置**: `rpc-core/src/test/java/com/rpc/faulttolerance/CircuitBreakerTest.java`

```java
package com.rpc.faulttolerance;

import com.rpc.faulttolerance.circuitbreaker.CircuitBreaker;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerImpl;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断器测试
 */
class CircuitBreakerTest {
    
    @Test
    void testStateTransition() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreakerImpl(
                "test-service",
                50.0f,      // 50% 失败率
                5,          // 最少 5 个请求
                1000,       // 1 秒休眠
                2           // 半开 2 个请求
        );
        
        // 初始状态：CLOSED
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        
        // 模拟连续失败
        for (int i = 0; i < 10; i++) {
            breaker.recordFailure();
        }
        
        // 状态应变为 OPEN
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());
        assertFalse(breaker.allowRequest());
        
        // 等待 1 秒
        Thread.sleep(1000);
        
        // 状态应变为 HALF_OPEN
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());
        assertTrue(breaker.allowRequest());
        
        // 模拟成功
        breaker.recordSuccess();
        
        // 状态应回到 CLOSED
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
    }
}
```

### 8.3 集成测试

📁 **文件位置**: `rpc-core/src/test/java/com/rpc/faulttolerance/FaultToleranceIntegrationTest.java`

```java
package com.rpc.faulttolerance;

import com.rpc.common.CircuitBreakerException;
import com.rpc.common.RpcException;
import com.rpc.config.RpcClientConfig;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.transport.netty.client.RpcNettyClient;
import org.junit.jupiter.api.Test;

/**
 * 容错机制集成测试
 */
class FaultToleranceIntegrationTest {
    
    @Test
    void testRetryOnNetworkFailure() {
        // TODO: 模拟网络失败场景，验证重试机制
        // 1. 启动 Mock 服务端
        // 2. 配置客户端重试
        // 3. 服务端前 2 次不响应，第 3 次成功
        // 4. 验证客户端最终收到响应
    }
    
    @Test
    void testCircuitBreakerOpensOnFailures() {
        // TODO: 测试熔断器打开
        // 1. 连续调用失败的服务
        // 2. 验证达到阈值后熔断器打开
        // 3. 验证后续请求被快速拒绝
    }
    
    @Test
    void testCircuitBreakerRecovery() throws InterruptedException {
        // TODO: 测试熔断器恢复
        // 1. 让熔断器打开
        // 2. 等待休眠时间
        // 3. 验证进入半开状态
        // 4. 模拟成功调用
        // 5. 验证回到关闭状态
    }
}
```

---

## 九、性能优化建议

当前的熔断器使用简单计数，可以改进为**滑动时间窗口**：

```java
// 使用 RingBuffer 记录最近 10 秒的请求
private static final int WINDOW_SIZE = 10; // 10 秒
private final AtomicLong[] bucketCounts = new AtomicLong[WINDOW_SIZE];
private final AtomicInteger[] bucketFailures = new AtomicInteger[WINDOW_SIZE];
```

### 9.2 异步重试

对于非关键业务，可以使用异步重试：

```java
CompletableFuture<RpcResponse> future = CompletableFuture.supplyAsync(() -> {
    try {
        return retryExecutor.executeWithRetry(request, callable);
    } catch (Exception e) {
        throw new CompletionException(e);
    }
});
```

---

## 十、生产环境最佳实践

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| 重试次数 | 3 | 过多会增加服务端压力 |
| 基础延迟 | 100ms | 平衡响应速度和重试效果 |
| 失败率阈值 | 50% | 半数失败即熔断 |
| 最小请求数 | 10 | 避免偶然失败误触发 |
| 熔断休眠 | 30s | 给服务足够恢复时间 |
| 半开请求数 | 5 | 小流量探测 |

### 10.2 监控告警

```java
// 定时打印熔断器状态
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.scheduleAtFixedRate(() -> {
    CircuitBreakerManager.getInstance().printStatus();
}, 0, 60, TimeUnit.SECONDS);
```

### 10.3 日志级别

- **INFO**: 熔断器状态变化、重试开始
- **DEBUG**: 重试延迟、统计详情
- **WARN**: 达到重试上限、熔断器打开
- **ERROR**: 不可重试异常、连续失败

---

## 十一、课后作业

### 11.1 基础题

1. 实现 `RoundRobinRetryStrategy`（轮询重试策略）
2. 为熔断器添加监控指标导出功能
3. 测试不同重试次数对成功率的影响

### 11.2 提高题

1. 实现基于滑动窗口的熔断器
2. 支持自定义降级策略 SPI 扩展
3. 实现熔断器状态持久化

---

## 十三、附录：完整代码清单

本节课我们实现了完整的 RPC 容错与重试机制：

✅ **异常分类体系**：区分可重试/不可重试异常  
✅ **重试机制**：指数退避 + 随机抖动  
✅ **熔断器**：三态转换 + 统计分析  
✅ **降级策略**：快速失败 + 默认值兜底  
✅ **整合应用**：无缝集成到 RPC 客户端  

**核心设计原则**：
1. **高内聚**：每个组件职责单一
2. **低耦合**：通过接口解耦，易于扩展
3. **可配置**：所有参数可调优
4. **可观测**：完善的日志和统计

**下节课预告**：第 13 课《异步调用与回调》
- CompletableFuture 深度应用
- 异步转同步桥接
- 回调函数链式调用
- One-Way 调用（无需响应）

---

## 附录：完整代码清单

| 模块 | 文件路径 | 说明 |
|------|----------|------|
| 异常类 | `rpc-core/src/main/java/com/rpc/common/` | RpcException、ErrorCode 等 |
| 重试策略 | `rpc-core/src/main/java/com/rpc/faulttolerance/retry/` | RetryStrategy、DefaultRetryStrategy |
| 重试执行器 | `rpc-core/src/main/java/com/rpc/faulttolerance/retry/RetryExecutor.java` | 执行重试逻辑 |
| 熔断器 | `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/` | CircuitBreaker 接口及实现 |
| 熔断器管理 | `rpc-core/src/main/java/com/rpc/faulttolerance/circuitbreaker/CircuitBreakerManager.java` | 单例管理器 |
| 降级策略 | `rpc-core/src/main/java/com/rpc/faulttolerance/degrade/` | DegradationPolicy 及实现 |
| 客户端增强 | `rpc-core/src/main/java/com/rpc/transport/netty/client/RpcNettyClient.java` | 整合容错机制 |
| 测试用例 | `rpc-core/src/test/java/com/rpc/faulttolerance/` | 单元测试 |

---

**恭喜你完成第 12 课的学习！** 🎉

现在你的 RPC 框架已经具备了企业级的容错能力，可以应对各种复杂的生产环境！

---

## 附录 B：熔断器调试技巧

### B.1 开启详细日志

```properties
# logback.xml 配置
<logger name="com.rpc.faulttolerance" level="DEBUG"/>
```

**输出示例：**
```
[DEBUG] 熔断器统计：service=HelloService, total=20, failed=12, failureRate=60%
[WARN ] 失败率超阈值，熔断器打开：HelloService (失败率=60%, 阈值=50%)
[INFO ] 熔断器从 OPEN 进入 HALF_OPEN: HelloService
[DEBUG] 执行 RPC 调用：HelloService.sayHello (尝试第 1 次)
[INFO ] 熔断器从 HALF_OPEN 进入 CLOSED: HelloService
```

### B.2 监控指标导出

```java
// 定时任务打印状态
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.scheduleAtFixedRate(() -> {
    CircuitBreakerManager.getInstance().printStatus();
}, 0, 60, TimeUnit.SECONDS);

// 集成 Prometheus
@GetMapping("/metrics/circuit-breakers")
public Map<String, Object> getCircuitBreakerMetrics() {
    Map<String, Object> metrics = new HashMap<>();
    // TODO: 导出各熔断器状态、统计信息
    return metrics;
}
```

### B.3 常见问题排查

| 问题现象 | 可能原因 | 排查方法 |
|---------|---------|---------|
| 频繁熔断 | 阈值设置过低 | 调高 failureRateThreshold 或 minNumberOfCalls |
| 长时间不恢复 | 休眠时间太短 | 增加 waitDurationInOpenState |
| 半开状态反复失败 | 服务未真正恢复 | 减少 permittedNumberOfCallsInHalfOpenState |
| 统计不准确 | 并发过高导致 | 考虑使用更精确的滑动窗口 |

---

## 附录 C：扩展阅读

### C.1 业界优秀实现对比

| 框架 | 特点 | 学习价值 |
|------|------|---------|
| Hystrix | 熔断器鼻祖，功能完善 | 已停止维护，但设计理念值得学习 |
| Resilience4j | 轻量级，函数式编程 | 推荐，Spring Cloud 官方推荐 |
| Sentinel | 阿里出品，实时监控 | 适合高并发场景 |
| 本课程实现 | 教学导向，代码简洁 | 最适合理解原理 |

### C.2 进阶话题

1. **滑动时间窗口算法**：比简单计数更精确
2. **自适应熔断器**：根据历史数据动态调整阈值
3. **多级熔断**：服务级别 + 实例级别双重保护
4. **熔断器链**：上下游服务联动熔断

### C.3 生产环境建议

1. **一定要配置监控告警**：熔断器打开时及时通知
2. **定期 review 配置参数**：根据实际流量调整
3. **灰度发布时特别注意**：新版本可能导致熔断策略变化
4. **结合限流使用**：熔断是最后一道防线，前面应该有降级、限流

---

**下一步学习**：
- 动手实现本课的所有代码
- 运行单元测试验证功能
- 修改参数观察不同效果
- 尝试实现滑动窗口优化版本

**准备好了吗？开始编码吧！** 💻
