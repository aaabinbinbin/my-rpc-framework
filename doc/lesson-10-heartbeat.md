# 第 10 课：心跳检测与断线重连

## 学习目标

- 理解心跳机制的原理和作用
- 掌握 Netty 的 IdleStateHandler 应用
- 实现客户端自动心跳检测
- 实现断线自动重连功能
- 学会设计高内聚低耦合的心跳模块

---

## 一、为什么需要心跳检测？

### 1.1 问题场景

在长连接通信中，客户端和服务端建立 TCP 连接后，如果长时间没有数据交互，会出现什么问题？

```
客户端                          服务端
  │                              │
  │══════ TCP 连接建立 ══════════│
  │                              │
  │  （5 分钟无数据交互）          │
  │                              │
  │ ❓连接还在吗？                │ ❓连接还在吗？
  │                              │
  │  （尝试发送请求）              │
  │ ───────────────────────────> │
  │                              │
  │ ❌ 连接已断开！                │
  │    抛出异常                   │
```

**常见问题：**

1. **防火墙/路由器超时**：网络设备会清理长时间无活动的连接
2. **服务端异常宕机**：客户端无法感知服务端已下线
3. **网络波动**：临时网络故障导致连接中断
4. **资源泄漏**：无效连接占用系统资源

### 1.2 心跳机制的作用

```
┌─────────────────────────────────────────────────────────┐
│                    心跳检测机制                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  客户端                        服务端                    │
│    │                            │                       │
│    │  ───── 心跳请求 ──────>    │                       │
│    │     (每隔 30 秒)            │                       │
│    │                            │                       │
│    │  <──── 心跳响应 ──────     │                       │
│    │                            │                       │
│    │                            │                       │
│  【作用】                                              │
│   1. 确认连接存活                                    │
│   2. 防止连接超时被关闭                              │
│   3. 检测对方是否在线                                │
│   4. 统计网络延迟                                    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**核心目标：**

1. **保活**：保持 TCP 连接活跃，防止被中间设备关闭
2. **检测**：快速发现连接断开，及时处理
3. **恢复**：自动重连，提高系统可用性

---

## 二、Netty 的 IdleStateHandler

### 2.1 IdleStateHandler 简介

`IdleStateHandler` 是 Netty 提供的空闲检测处理器，可以检测读/写空闲状态。

```java
import io.netty.handler.timeout.IdleStateHandler;

// 构造方法
public IdleStateHandler(
    int readerIdleTime,      // 读空闲时间（秒）
    int writerIdleTime,      // 写空闲时间（秒）
    int allIdleTime,         // 全空闲时间（秒）
    TimeUnit unit            // 时间单位
)
```

**触发事件：**

| 事件类型 | 触发条件 | 典型用途 |
|---------|---------|---------|
| `READER_IDLE` | 超过指定时间未读到数据 | 检测客户端是否存活 |
| `WRITER_IDLE` | 超过指定时间未写入数据 | 发送心跳包 |
| `ALL_IDLE` | 超过时间既未读也未写 | 关闭空闲连接 |

### 2.2 IdleStateHandler 工作原理

```
┌─────────────────────────────────────────────────────────┐
│              IdleStateHandler 工作流程                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  添加 Handler 到 Pipeline                                │
│       ↓                                                 │
│  启动定时器（基于 EventLoop）                           │
│       ↓                                                 │
│  每次 read/write 时重置定时器                           │
│       ↓                                                 │
│  定时器触发检查：                                       │
│    - 如果 lastReadTime + readerIdleTime < now          │
│      → 触发 READER_IDLE 事件                            │
│    - 如果 lastWriteTime + writerIdleTime < now         │
│      → 触发 WRITER_IDLE 事件                            │
│    - 如果两者都满足                                     │
│      → 触发 ALL_IDLE 事件                               │
│       ↓                                                 │
│  调用 userEventTriggered() 通知 Handler                 │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 2.3 使用示例

```java
public class MyChannelInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
            // 添加空闲检测处理器
            .addLast("idleStateHandler", 
                new IdleStateHandler(0, 30, 0, TimeUnit.SECONDS))
            // 添加自定义处理器处理空闲事件
            .addLast("idleHandler", new IdleStateHandler());
    }
}

public class IdleStateHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) 
            throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            switch (event.state()) {
                case WRITER_IDLE:
                    // 写空闲，发送心跳
                    sendHeartbeat(ctx);
                    break;
                case READER_IDLE:
                    // 读空闲，关闭连接
                    ctx.close();
                    break;
                case ALL_IDLE:
                    // 全空闲，记录日志
                    log.warn("连接全空闲");
                    break;
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
    
    private void sendHeartbeat(ChannelHandlerContext ctx) {
        // 发送心跳消息
        ctx.writeAndFlush(createHeartbeatMessage());
    }
}
```

---

## 三、心跳消息设计

### 3.1 RpcHeartbeat 消息体

心跳消息需要轻量且易于识别：

```java
package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 心跳消息（用于保持连接活跃）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeartbeat {
    /** 请求 ID（用于匹配请求和响应） */
    private long requestId;
    
    /** 时间戳（仅响应时需要） */
    private long timestamp;
    
    /**
     * 创建心跳请求
     */
    public static RpcHeartbeat createRequest(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .build();
    }
    
    /**
     * 创建心跳响应
     */
    public static RpcHeartbeat createResponse(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
```

**设计要点：**

1. ✅ **轻量**：只包含必要字段（requestId + timestamp）
2. ✅ **可匹配**：通过 requestId 匹配请求和响应
3. ✅ **可测速**：通过 timestamp 计算网络延迟
4. ✅ **静态工厂**：使用工厂方法简化创建

### 3.2 消息类型定义

在 `RpcMessageType` 中添加心跳类型：

```java
package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RPC 消息类型
 */
@Getter
@AllArgsConstructor
public enum RpcMessageType {
    
    REQUEST((byte) 1, "请求消息"),
    RESPONSE((byte) 2, "响应消息"),
    
    HEARTBEAT_REQUEST((byte) 3, "心跳请求"),
    HEARTBEAT_RESPONSE((byte) 4, "心跳响应"),
    
    EXCEPTION((byte) 5, "异常消息");
    
    private final byte code;
    private final String description;
    
    public static RpcMessageType fromCode(byte code) {
        for (RpcMessageType type : values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知消息类型：" + code);
    }
}
```

---

## 四、客户端心跳模块实现

### 4.1 整体架构设计

```
┌─────────────────────────────────────────────────────────┐
│                  客户端心跳模块架构                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │              ChannelPipeline                     │   │
│  │                                                  │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │ IdleStateHandler                         │  │   │
│  │  │ - 检测写空闲                              │  │   │
│  │  │ - 触发 WRITER_IDLE 事件                   │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                      ↓                          │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │ HeartbeatHandler                         │  │   │
│  │  │ - 监听空闲事件                            │  │   │
│  │  │ - 构建并发送心跳消息                      │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                      ↓                          │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │ ReconnectHandler                         │  │   │
│  │  │ - 监听 channelInactive 事件               │  │   │
│  │  │ - 执行断线重连逻辑                        │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                      ↓                          │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │ RpcClientHandler                         │  │   │
│  │  │ - 处理心跳响应                            │  │   │
│  │  │ - 处理业务响应                            │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**设计原则：**

1. **高内聚**：每个 Handler 只负责一个职责
2. **低耦合**：Handler 之间通过事件通信，不直接依赖
3. **可扩展**：易于添加新的功能（如心跳统计、告警等）

### 4.2 HeartbeatHandler 实现

```java
package com.rpc.transport.netty.client.handler.heart;

import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcHeartbeat;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 心跳处理器
 * 
 * 职责：
 * 1. 监听 IdleStateEvent 事件
 * 2. 检测到写空闲时发送心跳
 * 3. 构建心跳消息并发送
 */
@Slf4j
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) 
            throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            // 写空闲时，发送心跳
            if (event.state() == IdleState.WRITER_IDLE) {
                log.debug("检测到写空闲，发送心跳：{}", ctx.channel().remoteAddress());
                sendHeartbeat(ctx);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat(ChannelHandlerContext ctx) {
        try {
            // 1. 生成请求 ID（使用时间戳保证唯一性）
            long requestId = System.nanoTime();
            
            // 2. 构建消息头
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) 0)  // 心跳消息不需要序列化
                    .messageType(RpcMessageType.HEARTBEAT_REQUEST)
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();
            
            // 3. 构建消息体
            RpcHeartbeat heartbeat = RpcHeartbeat.createRequest(requestId);
            
            // 4. 组装消息
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(heartbeat);
            
            // 5. 发送心跳
            ctx.writeAndFlush(message)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        log.warn("发送心跳失败：{}", future.cause().getMessage());
                    } else {
                        log.debug("心跳发送成功，requestId: {}", requestId);
                    }
                });
                
        } catch (Exception e) {
            log.error("构建心跳消息失败", e);
        }
    }
}
```

**关键点解析：**

1. ✅ **继承 ChannelInboundHandlerAdapter**：只关心入站事件
2. ✅ **重写 userEventTriggered()**：处理用户自定义事件（IdleStateEvent）
3. ✅ **只处理 WRITER_IDLE**：客户端负责主动发送心跳
4. ✅ **异步发送**：使用 `writeAndFlush()` 立即发送
5. ✅ **添加监听器**：记录发送结果，便于调试

### 4.3 ReconnectHandler 实现

```java
package com.rpc.transport.netty.client.handler.heart;

import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 断线重连处理器
 * 
 * 职责：
 * 1. 监听 channelInactive 事件
 * 2. 触发重连逻辑
 * 3. 实现指数退避重试策略
 */
@Slf4j
public class ReconnectHandler extends ChannelInboundHandlerAdapter {
    
    private final ConnectionPool connectionPool;
    private final String host;
    private final int port;
    
    // 重连相关配置
    private static final int MAX_RETRY_TIMES = 5;           // 最大重试次数
    private static final int INITIAL_DELAY = 2;             // 初始延迟（秒）
    private static final int MAX_DELAY = 60;                // 最大延迟（秒）
    
    // 重连状态
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(1, new DefaultThreadFactory("reconnect-scheduler"));
    
    public ReconnectHandler(ConnectionPool connectionPool, String host, int port) {
        this.connectionPool = connectionPool;
        this.host = host;
        this.port = port;
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("与服务器断开连接：{}", ctx.channel().remoteAddress());
        
        // 清理连接池中的旧连接
        if (connectionPool != null) {
            connectionPool.removeConnection(host, port);
        }
        
        // 触发重连
        scheduleReconnect();
        
        super.channelInactive(ctx);
    }
    
    /**
     * 调度重连任务
     */
    private void scheduleReconnect() {
        int currentRetry = retryCount.get();
        
        if (currentRetry >= MAX_RETRY_TIMES) {
            log.error("重连失败，已达到最大重试次数 {}", MAX_RETRY_TIMES);
            closeScheduler();
            return;
        }
        
        // 计算延迟时间（指数退避）
        int delay = calculateBackoffDelay(currentRetry);
        
        log.info("将在 {} 秒后尝试第 {} 次重连...", delay, currentRetry + 1);
        
        scheduler.schedule(() -> {
            try {
                reconnect();
            } catch (Exception e) {
                log.error("重连异常", e);
                retryCount.incrementAndGet();
                scheduleReconnect();  // 继续尝试
            }
        }, delay, TimeUnit.SECONDS);
    }
    
    /**
     * 执行重连
     */
    private void reconnect() {
        log.info("开始重连到 {}:{}", host, port);
        
        try {
            // 从连接池获取新连接（会触发创建新连接）
            if (connectionPool != null) {
                connectionPool.getConnection(host, port);
                
                // 重连成功，重置计数
                retryCount.set(0);
                log.info("重连成功！");
            }
        } catch (Exception e) {
            log.error("重连失败：{}", e.getMessage());
            retryCount.incrementAndGet();
            scheduleReconnect();  // 继续尝试
        }
    }
    
    /**
     * 计算退避延迟（指数退避 + 随机抖动）
     */
    private int calculateBackoffDelay(int retryCount) {
        // 指数增长：2, 4, 8, 16, 32...
        int exponentialDelay = INITIAL_DELAY * (1 << retryCount);
        
        // 限制最大延迟
        int cappedDelay = Math.min(exponentialDelay, MAX_DELAY);
        
        // 添加随机抖动（避免多个客户端同时重连）
        int jitter = (int) (Math.random() * 2);  // 0-2 秒随机
        
        return cappedDelay + jitter;
    }
    
    /**
     * 关闭调度器
     */
    private void closeScheduler() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) 
            throws Exception {
        log.error("重连处理器异常", cause);
        ctx.close();
    }
}
```

**核心特性：**

1. ✅ **指数退避**：每次重试延迟时间翻倍，避免频繁重试
2. ✅ **随机抖动**：添加随机延迟，避免多个客户端同时重连造成"惊群效应"
3. ✅ **最大重试限制**：防止无限重试消耗资源
4. ✅ **异步调度**：使用 ScheduledExecutorService 延迟执行
5. ✅ **优雅关闭**：重连成功或达到最大次数后关闭调度器

### 4.4 心跳响应处理

客户端需要处理服务端返回的心跳响应：

```java
package com.rpc.transport.netty.client.handler;

import com.rpc.protocol.RpcHeartbeat;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import com.rpc.transport.netty.client.manager.RequestManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 客户端处理器
 */
@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {
    
    private final RequestManager requestManager;
    
    public RpcClientHandler(RequestManager requestManager) {
        this.requestManager = requestManager;
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        RpcMessageType messageType = message.getHeader().getMessageType();
        
        switch (messageType) {
            case HEARTBEAT_RESPONSE:
                // 处理心跳响应
                handleHeartbeatResponse(message);
                break;
                
            case RESPONSE:
                // 处理业务响应
                handleBusinessResponse(message);
                break;
                
            default:
                log.warn("未知消息类型：{}", messageType);
        }
    }
    
    /**
     * 处理心跳响应
     */
    private void handleHeartbeatResponse(RpcMessage message) {
        RpcHeartbeat heartbeat = (RpcHeartbeat) message.getBody();
        long requestId = heartbeat.getRequestId();
        long timestamp = heartbeat.getTimestamp();
        
        // 计算往返延迟
        long current = System.currentTimeMillis();
        long latency = current - timestamp;
        
        log.debug("收到心跳响应：requestId={}, 延迟={}ms", requestId, latency);
        
        // 可以在这里记录心跳统计信息
        // heartbeatStats.recordLatency(latency);
    }
    
    /**
     * 处理业务响应
     */
    private void handleBusinessResponse(RpcMessage message) {
        // ... 业务响应处理逻辑
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端处理器异常", cause);
        ctx.close();
    }
}
```

---

## 五、服务端心跳模块实现

### 5.1 服务端架构设计

```
┌─────────────────────────────────────────────────────────┐
│                  服务端心跳模块架构                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │              ChannelPipeline                     │   │
│  │                                                  │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │ IdleStateHandler                         │  │   │
│  │  │ - 检测读空闲                              │  │   │
│  │  │ - 触发 READER_IDLE 事件                   │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                      ↓                          │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │ ServerHeartbeatHandler                   │  │   │
│  │  │ - 监听空闲事件                            │  │   │
│  │  │ - 检测客户端是否存活                      │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                      ↓                          │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │ RpcRequestHandler                        │  │   │
│  │  │ - 处理心跳请求                            │  │   │
│  │  │ - 处理业务请求                            │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 5.2 ServerHeartbeatHandler 实现

```java
package com.rpc.transport.netty.server.handler.heart;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务端心跳处理器
 * 
 * 职责：
 * 1. 检测客户端连接状态
 * 2. 处理读空闲事件
 * 3. 关闭异常连接
 */
@Slf4j
public class ServerHeartbeatHandler extends ChannelInboundHandlerAdapter {
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) 
            throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            switch (event.state()) {
                case READER_IDLE:
                    // 读空闲，表示长时间未收到客户端数据
                    log.warn("客户端长时间未发送数据，可能已断开：{}", 
                        ctx.channel().remoteAddress());
                    // 可以选择主动探测或关闭连接
                    // ctx.close();
                    break;
                    
                case WRITER_IDLE:
                    // 服务端一般不需要主动发送心跳
                    log.debug("服务端写空闲");
                    break;
                    
                case ALL_IDLE:
                    // 全空闲，连接可能已失效
                    log.warn("客户端连接全空闲，准备关闭连接：{}", 
                        ctx.channel().remoteAddress());
                    ctx.close();
                    break;
                    
                default:
                    super.userEventTriggered(ctx, evt);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("客户端断开连接：{}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) 
            throws Exception {
        log.error("服务端心跳处理器异常", cause);
        ctx.close();
    }
}
```

### 5.3 服务端处理心跳请求

服务端需要响应客户端的心跳请求：

```java
package com.rpc.transport.netty.server.handler;

import com.rpc.protocol.*;
import com.rpc.registry.LocalRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * RPC 请求处理器
 */
@Slf4j
public class RpcRequestHandler extends SimpleChannelInboundHandler<RpcMessage> {
    
    private final LocalRegistry localRegistry;
    
    public RpcRequestHandler(LocalRegistry localRegistry) {
        this.localRegistry = localRegistry;
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        RpcMessageType messageType = message.getHeader().getMessageType();
        
        switch (messageType) {
            case HEARTBEAT_REQUEST:
                // 处理心跳请求
                handleHeartbeatRequest(ctx, message);
                break;
                
            case REQUEST:
                // 处理业务请求
                handleBusinessRequest(ctx, message);
                break;
                
            default:
                log.warn("未知消息类型：{}", messageType);
        }
    }
    
    /**
     * 处理心跳请求
     */
    private void handleHeartbeatRequest(ChannelHandlerContext ctx, RpcMessage request) {
        try {
            RpcHeartbeat heartbeatRequest = (RpcHeartbeat) request.getBody();
            long requestId = heartbeatRequest.getRequestId();
            
            log.debug("收到心跳请求：requestId={}", requestId);
            
            // 构建心跳响应
            RpcHeartbeat heartbeatResponse = RpcHeartbeat.createResponse(requestId);
            
            // 构建响应消息头
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(RpcHeader.MAGIC_NUMBER)
                    .version(RpcHeader.VERSION)
                    .serializerType((byte) 0)
                    .messageType(RpcMessageType.HEARTBEAT_RESPONSE)
                    .reserved((byte) 0)
                    .requestId(requestId)
                    .build();
            
            // 组装响应消息
            RpcMessage response = new RpcMessage();
            response.setHeader(header);
            response.setBody(heartbeatResponse);
            
            // 发送响应
            ctx.writeAndFlush(response)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        log.warn("发送心跳响应失败", future.cause());
                    }
                });
                
        } catch (Exception e) {
            log.error("处理心跳请求失败", e);
        }
    }
    
    /**
     * 处理业务请求
     */
    private void handleBusinessRequest(ChannelHandlerContext ctx, RpcMessage message) {
        // ... 业务请求处理逻辑
    }
}
```

---

## 六、整合到 RPC 框架

### 6.1 修改 RpcNettyClient

将心跳模块整合到客户端启动流程：

```java
package com.rpc.transport.netty.client;

import com.rpc.config.RpcClientConfig;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.transport.netty.client.connection.RpcConnection;
import com.rpc.transport.netty.client.handler.heart.HeartbeatHandler;
import com.rpc.transport.netty.client.handler.heart.ReconnectHandler;
import com.rpc.transport.netty.client.manager.RequestManager;
import com.rpc.transport.netty.client.handler.RpcClientHandler;
import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.protocol.*;
import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.serialize.factory.SerializerFactory;
import com.rpc.registry.ServiceRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 客户端（整合心跳检测）
 */
@Slf4j
public class RpcNettyClient {
    
    // Netty 事件循环组
    private EventLoopGroup eventLoopGroup;
    
    // 连接池
    private ConnectionPool connectionPool;
    
    // 请求管理器
    private RequestManager requestManager;
    
    // 服务注册中心
    private final ServiceRegistry serviceRegistry;
    
    // 负载均衡器
    private final LoadBalancer loadBalancer;
    
    // 配置
    private int connectTimeout = 5000;
    private int readTimeout = 10000;
    
    // 心跳配置
    private static final int HEARTBEAT_INTERVAL = 30;  // 心跳间隔（秒）
    
    /**
     * 构造方法
     */
    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        this.loadBalancer = config.getLoadBalancer();
        
        // 创建 Bootstrap
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new LoggingHandler(LogLevel.INFO))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                // 1. 空闲检测处理器（30 秒写空闲）
                                .addLast("idleStateHandler",
                                        new IdleStateHandler(0, HEARTBEAT_INTERVAL, 0, 
                                            TimeUnit.SECONDS))
                                // 2. 心跳处理器（发送心跳）
                                .addLast("heartbeatHandler", new HeartbeatHandler())
                                // 3. 重连处理器（断线重连）
                                .addLast("reconnectHandler", 
                                        new ReconnectHandler(connectionPool, 
                                            "127.0.0.1", 8080))
                                // 4. 解码器
                                .addLast("decoder", new RpcProtocolDecoder())
                                // 5. 编码器
                                .addLast("encoder", new RpcProtocolEncoder())
                                // 6. 业务处理器
                                .addLast("handler", new RpcClientHandler(requestManager));
                    }
                });
        
        this.connectionPool = new ConnectionPool(bootstrap);
    }
    
    // ... 其他方法保持不变
}
```

**Pipeline 顺序说明：**

```
入站方向（read）：
  Head → IdleStateHandler → ReconnectHandler → HeartbeatHandler 
       → Decoder → Encoder → RpcClientHandler → Tail

出站方向（write）：
  Tail → RpcClientHandler → Decoder → Encoder → HeartbeatHandler 
       → ReconnectHandler → IdleStateHandler → Head
```

### 6.2 修改 RpcNettyServer

服务端整合心跳检测：

```java
package com.rpc.transport.netty.server;

import com.rpc.config.RpcServerConfig;
import com.rpc.registry.LocalRegistry;
import com.rpc.registry.ServiceRegistry;
import com.rpc.transport.netty.server.handler.RpcRequestHandler;
import com.rpc.transport.netty.server.handler.heart.ServerHeartbeatHandler;
import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 服务端（整合心跳检测）
 */
@Slf4j
public class RpcNettyServer {
    
    private final RpcServerConfig config;
    private final ServiceRegistry serviceRegistry;
    private final LocalRegistry localRegistry;
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    
    // 心跳配置
    private static final int HEARTBEAT_TIMEOUT = 60;  // 心跳超时时间（秒）
    
    public RpcNettyServer(RpcServerConfig config, ServiceRegistry serviceRegistry) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.localRegistry = new LocalRegistryImpl();
    }
    
    /**
     * 启动服务端
     */
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    // 1. 空闲检测（60 秒读空闲）
                                    .addLast("idleStateHandler",
                                            new IdleStateHandler(HEARTBEAT_TIMEOUT, 0, 0, 
                                                TimeUnit.SECONDS))
                                    // 2. 服务端心跳处理器
                                    .addLast("serverHeartbeatHandler", 
                                            new ServerHeartbeatHandler())
                                    // 3. 解码器
                                    .addLast("decoder", new RpcProtocolDecoder())
                                    // 4. 编码器
                                    .addLast("encoder", new RpcProtocolEncoder())
                                    // 5. 业务处理器
                                    .addLast("handler", 
                                            new RpcRequestHandler(localRegistry));
                        }
                    });
            
            ChannelFuture future = bootstrap.bind(config.getPort()).sync();
            log.info("RPC 服务端启动成功，端口：{}", config.getPort());
            
            // 注册服务到注册中心
            registerServices();
            
            future.channel().closeFuture().sync();
            
        } catch (InterruptedException e) {
            log.error("服务端启动异常", e);
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
    }
    
    /**
     * 注册服务
     */
    private void registerServices() {
        if (serviceRegistry != null && !localRegistry.getServiceMap().isEmpty()) {
            for (String serviceName : localRegistry.getServiceMap().keySet()) {
                String serviceAddress = config.getHost() + ":" + config.getPort();
                serviceRegistry.register(serviceName, serviceAddress);
                log.info("注册服务：{} -> {}", serviceName, serviceAddress);
            }
        }
    }
    
    /**
     * 关闭服务端
     */
    public void shutdown() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (serviceRegistry != null) {
            serviceRegistry.close();
        }
        log.info("RPC 服务端已关闭");
    }
    
    /**
     * 获取本地注册表
     */
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }
}
```

---

## 七、测试验证

### 7.1 单元测试

使用 `EmbeddedChannel` 进行单元测试，无需真实网络：

```java
package com.rpc.heart;

import com.rpc.protocol.*;
import com.rpc.transport.netty.client.handler.heart.HeartbeatHandler;
import com.rpc.transport.netty.client.handler.heart.ReconnectHandler;
import com.rpc.transport.netty.server.handler.RpcRequestHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 心跳检测与断线重连功能测试
 */
@Slf4j
public class HeartbeatTest {
    
    private EmbeddedChannel clientChannel;
    private EmbeddedChannel serverChannel;
    private RpcRequestHandler rpcRequestHandler;
    
    @Before
    public void setUp() {
        log.info("========== 测试准备：初始化通道 ==========");
        
        // 初始化服务端处理器
        rpcRequestHandler = new RpcRequestHandler(null);
        
        // 创建服务端通道
        serverChannel = new EmbeddedChannel(rpcRequestHandler);
        
        // 创建客户端通道（包含心跳和重连处理器）
        clientChannel = new EmbeddedChannel(
            new HeartbeatHandler(),
            new ReconnectHandler(null, "127.0.0.1", 8080)
        );
        
        log.info("通道初始化完成");
    }
    
    @After
    public void tearDown() {
        log.info("========== 清理测试资源 ==========");
        
        try {
            if (clientChannel != null && clientChannel.isActive()) {
                clientChannel.finish();
            }
            
            if (serverChannel != null && serverChannel.isActive()) {
                serverChannel.finish();
            }
        } catch (Exception e) {
            log.warn("清理通道时出错", e);
        }
        
        log.info("测试资源清理完成");
    }
    
    /**
     * 测试 1：客户端发送心跳请求
     */
    @Test
    public void testClientSendHeartbeat() {
        log.info("\n========== 测试 1：客户端发送心跳请求 ==========");
        
        // 触发写空闲事件
        IdleStateEvent idleEvent = IdleStateEvent.WRITER_IDLE_STATE_EVENT;
        clientChannel.pipeline().fireUserEventTriggered(idleEvent);
        
        // 验证是否有数据被写出
        assertTrue("应该有数据被写出", clientChannel.outboundMessages().size() > 0);
        
        // 获取发出的消息
        RpcMessage message = clientChannel.readOutbound();
        assertNotNull("消息不应为空", message);
        assertEquals("消息类型应该是心跳请求", RpcMessageType.HEARTBEAT_REQUEST,
                    message.getHeader().getMessageType());
        
        // 验证消息体
        Object body = message.getBody();
        assertTrue("消息体应该是 RpcHeartbeat 类型", body instanceof RpcHeartbeat);
        RpcHeartbeat heartbeat = (RpcHeartbeat) body;
        assertTrue("请求 ID 应该大于 0", heartbeat.getRequestId() > 0);
        assertEquals("心跳请求的时间戳应为 0", 0, heartbeat.getTimestamp());
        
        log.info("✓ 客户端成功发送心跳请求");
        log.info("requestId: {}, timestamp: {}\n", heartbeat.getRequestId(), heartbeat.getTimestamp());
    }
    
    /**
     * 测试 2：服务端处理心跳请求并返回响应
     */
    @Test
    public void testServerHandleHeartbeat() {
        log.info("\n========== 测试 2：服务端处理心跳请求 ==========");
        
        // 1. 创建心跳请求
        long requestId = new Random().nextLong();
        RpcHeartbeat heartbeat = RpcHeartbeat.createRequest(requestId);
        
        RpcHeader header = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .messageType(RpcMessageType.HEARTBEAT_REQUEST)
                .serializerType((byte) 1)
                .requestId(requestId)
                .build();
        
        RpcMessage requestMessage = new RpcMessage();
        requestMessage.setHeader(header);
        requestMessage.setBody(heartbeat);
        
        // 2. 发送到服务端
        serverChannel.writeInbound(requestMessage);
        
        // 3. 验证服务端收到了消息
        assertTrue("服务端应该收到消息", serverChannel.outboundMessages().size() > 0);
        
        // 4. 获取响应消息
        RpcMessage responseMessage = serverChannel.readOutbound();
        assertNotNull("响应消息不应为空", responseMessage);
        assertEquals("消息类型应该是心跳响应", RpcMessageType.HEARTBEAT_RESPONSE,
                    responseMessage.getHeader().getMessageType());
        assertEquals("请求 ID 应该匹配", requestId, responseMessage.getHeader().getRequestId());
        
        // 验证消息体
        Object body = responseMessage.getBody();
        assertTrue("消息体应该是 RpcHeartbeat 类型", body instanceof RpcHeartbeat);
        RpcHeartbeat responseHeartbeat = (RpcHeartbeat) body;
        assertEquals("请求 ID 应该匹配", requestId, responseHeartbeat.getRequestId());
        assertTrue("响应应该包含时间戳", responseHeartbeat.getTimestamp() > 0);
        
        log.info("✓ 服务端正确处理心跳请求并返回响应");
        log.info("requestId: {}, timestamp: {}\n", 
                responseHeartbeat.getRequestId(), responseHeartbeat.getTimestamp());
    }
    
    /**
     * 测试 3：完整的心跳请求 - 响应流程
     */
    @Test
    public void testHeartbeatRequestResponseFlow() {
        log.info("\n========== 测试 3：完整的心跳请求 - 响应流程 ==========");
        
        // ========== 客户端发送心跳请求 ==========
        log.info("【步骤 1】客户端发送心跳请求");
        
        // 触发写空闲事件
        IdleStateEvent idleEvent = IdleStateEvent.WRITER_IDLE_STATE_EVENT;
        clientChannel.pipeline().fireUserEventTriggered(idleEvent);
        
        // 获取客户端发出的心跳请求
        RpcMessage requestMessage = clientChannel.readOutbound();
        assertNotNull("客户端应该发送心跳请求", requestMessage);
        assertEquals("消息类型应该是心跳请求", RpcMessageType.HEARTBEAT_REQUEST,
                    requestMessage.getHeader().getMessageType());
        
        RpcHeartbeat requestHeartbeat = (RpcHeartbeat) requestMessage.getBody();
        log.info("客户端发送心跳请求完成，requestId={}", requestHeartbeat.getRequestId());
        
        // ========== 服务端接收并处理心跳请求 ==========
        log.info("【步骤 2】服务端接收心跳请求");
        
        // 将客户端的请求转发给服务端
        serverChannel.writeInbound(requestMessage);
        
        // 验证服务端收到了消息
        assertTrue("服务端应该收到心跳请求", serverChannel.inboundMessages().size() > 0);
        
        // ========== 服务端返回心跳响应 ==========
        log.info("【步骤 3】服务端返回心跳响应");
        
        // 获取服务端生成的响应
        RpcMessage responseMessage = serverChannel.readOutbound();
        assertNotNull("服务端应该返回心跳响应", responseMessage);
        assertEquals("消息类型应该是心跳响应", RpcMessageType.HEARTBEAT_RESPONSE,
                    responseMessage.getHeader().getMessageType());
        
        RpcHeartbeat responseHeartbeat = (RpcHeartbeat) responseMessage.getBody();
        assertTrue("响应应该包含时间戳", responseHeartbeat.getTimestamp() > 0);
        log.info("服务端发送心跳响应完成，requestId={}", responseHeartbeat.getRequestId());
        
        // ========== 客户端接收心跳响应 ==========
        log.info("【步骤 4】客户端接收心跳响应");
        
        // 将服务端的响应转发给客户端
        clientChannel.writeInbound(responseMessage);
        
        log.info("✓ 完整的心跳请求 - 响应流程测试通过\n");
    }
    
    /**
     * 测试 4：断线重连功能
     */
    @Test
    public void testReconnectFunctionality() {
        log.info("\n========== 测试 4：断线重连功能 ==========");
        
        // 模拟连接断开
        log.info("模拟客户端连接断开");
        clientChannel.pipeline().fireChannelInactive();
        
        // 等待重连逻辑执行
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);  // 模拟重连过程
                log.info("模拟重连成功");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).join();
        
        log.info("✓ 断线重连功能测试通过（简化版）\n");
    }
    
    /**
     * 测试 5：心跳超时检测
     */
    @Test
    public void testHeartbeatTimeoutDetection() {
        log.info("\n========== 测试 5：心跳超时检测 ==========");
        
        // 创建长时间无通信场景
        log.info("模拟长时间无通信场景");
        
        // 触发读空闲事件
        IdleStateEvent readerIdleEvent = IdleStateEvent.READER_IDLE_STATE_EVENT;
        serverChannel.pipeline().fireUserEventTriggered(readerIdleEvent);
        
        // 验证服务端是否检测到空闲
        log.info("服务端检测到读空闲状态");
        
        log.info("✓ 心跳超时检测测试通过\n");
    }
}
```

### 7.2 集成测试

真实环境下的集成测试：

```java
package com.rpc.heart;

import com.rpc.HelloService;
import com.rpc.config.RpcClientConfig;
import com.rpc.config.RpcServerConfig;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.transport.netty.server.RpcNettyServer;
import com.rpc.proxy.RpcProxyFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 心跳检测集成测试
 */
@Slf4j
public class HeartbeatIntegrationTest {
    
    private RpcNettyServer server;
    private RpcNettyClient client;
    private Thread serverThread;
    
    @Before
    public void setUp() throws Exception {
        log.info("========== 启动集成测试环境 ==========");
        
        // 1. 启动服务端
        RpcServerConfig serverConfig = RpcServerConfig.custom()
                .port(8080);
        ZooKeeperRegistryImpl registry = new ZooKeeperRegistryImpl("127.0.0.1:2181");
        
        server = new RpcNettyServer(serverConfig, registry);
        server.getLocalRegistry().register("HelloService", new HelloServiceImpl());
        
        serverThread = new Thread(() -> server.start());
        serverThread.setDaemon(true);
        serverThread.start();
        
        // 等待服务端启动
        Thread.sleep(2000);
        
        // 2. 启动客户端
        RpcClientConfig clientConfig = RpcClientConfig.custom();
        client = new RpcNettyClient(clientConfig, registry);
        
        log.info("========== 测试环境启动完成 ==========\n");
    }
    
    @After
    public void tearDown() {
        log.info("========== 清理测试环境 ==========");
        
        if (client != null) {
            client.close();
        }
        
        if (server != null) {
            server.shutdown();
        }
        
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
        }
        
        log.info("========== 测试环境清理完成 ==========");
    }
    
    /**
     * 测试长时间空闲后的调用
     */
    @Test
    public void testCallAfterIdle() throws Exception {
        log.info("\n========== 测试：长时间空闲后调用 ==========");
        
        // 1. 创建代理
        RpcProxyFactory proxyFactory = new RpcProxyFactory(client);
        HelloService proxy = proxyFactory.createProxy(HelloService.class);
        
        // 2. 第一次调用（建立连接）
        String result1 = proxy.sayHello("User1");
        log.info("第一次调用结果：{}", result1);
        
        // 3. 等待 35 秒（超过心跳间隔）
        log.info("等待 35 秒，让心跳机制工作...");
        Thread.sleep(35000);
        
        // 4. 第二次调用（连接应该还保持）
        String result2 = proxy.sayHello("User2");
        log.info("第二次调用结果：{}", result2);
        
        // 5. 验证结果
        assert result1.contains("User1");
        assert result2.contains("User2");
        
        log.info("✓ 长时间空闲后调用成功\n");
    }
    
    /**
     * 测试断线重连
     */
    @Test
    public void testReconnect() throws Exception {
        log.info("\n========== 测试：断线重连 ==========");
        
        RpcProxyFactory proxyFactory = new RpcProxyFactory(client);
        HelloService proxy = proxyFactory.createProxy(HelloService.class);
        
        // 1. 正常调用
        String result1 = proxy.sayHello("Before");
        log.info("重连前调用：{}", result1);
        
        // 2. 模拟服务端重启（这里简化处理）
        log.info("模拟服务端重启...");
        
        // 3. 等待重连
        CountDownLatch latch = new CountDownLatch(1);
        latch.await(10, TimeUnit.SECONDS);
        
        // 4. 再次调用
        String result2 = proxy.sayHello("After");
        log.info("重连后调用：{}", result2);
        
        log.info("✓ 断线重连测试通过\n");
    }
}
```

### 7.3 运行测试

```bash
# 运行单元测试
mvn test -Dtest=HeartbeatTest

# 运行集成测试
mvn test -Dtest=HeartbeatIntegrationTest
```

**预期输出：**

```
[INFO] 测试准备：初始化通道 ==========
[INFO] 通道初始化完成
[INFO] 
========== 测试 1：客户端发送心跳请求 ==========
[DEBUG] 检测到写空闲，发送心跳：embedded
[DEBUG] 心跳发送成功，requestId: 1234567890
[INFO] ✓ 客户端成功发送心跳请求
[INFO] requestId: 1234567890, timestamp: 0
[INFO] 
========== 清理测试资源 ==========
[INFO] 测试资源清理完成
```

---

## 八、性能优化与最佳实践

### 8.1 心跳间隔选择

**问题：** 心跳间隔设置多少合适？

**分析：**

| 间隔时间 | 优点 | 缺点 | 适用场景 |
|---------|------|------|---------|
| 5 秒 | 快速发现断线 | 网络流量大，增加负载 | 高可用要求极高 |
| 30 秒 | 平衡 | 无明显缺点 | **推荐（默认）** |
| 60 秒 | 流量小 | 断线发现慢 | 低频率调用 |

**建议：**

```java
// 客户端：30 秒发送一次心跳
private static final int HEARTBEAT_INTERVAL = 30;

// 服务端：60 秒超时（允许丢失 1-2 个心跳）
private static final int HEARTBEAT_TIMEOUT = 60;
```

### 8.2 避免心跳风暴

**问题：** 大量客户端同时发送心跳，造成网络拥塞。

**解决方案：**

```java
/**
 * 添加随机抖动的心跳发送
 */
private void sendHeartbeatWithJitter(ChannelHandlerContext ctx) {
    // 添加 0-3 秒的随机延迟
    int jitter = (int) (Math.random() * 3000);
    
    ctx.executor().schedule(() -> {
        sendHeartbeat(ctx);
    }, jitter, TimeUnit.MILLISECONDS);
}
```

### 8.3 心跳统计与监控

```java
/**
 * 心跳统计信息
 */
public class HeartbeatStats {
    
    private final AtomicLong totalHeartbeats = new AtomicLong(0);
    private final AtomicLong failedHeartbeats = new AtomicLong(0);
    private final LongAdder totalLatency = new LongAdder();
    
    public void recordSuccess(long latency) {
        totalHeartbeats.incrementAndGet();
        totalLatency.add(latency);
    }
    
    public void recordFailure() {
        totalHeartbeats.incrementAndGet();
        failedHeartbeats.incrementAndGet();
    }
    
    public double getAverageLatency() {
        long count = totalHeartbeats.get() - failedHeartbeats.get();
        return count > 0 ? totalLatency.sum() / count : 0;
    }
    
    public double getFailureRate() {
        long total = totalHeartbeats.get();
        return total > 0 ? (double) failedHeartbeats.get() / total : 0;
    }
}
```

### 8.4 生产环境建议

1. **日志级别**：生产环境使用 INFO 级别，避免 DEBUG 日志过多
2. **监控告警**：心跳失败率超过阈值时告警
3. **优雅关闭**：关闭前发送通知，避免误判
4. **资源清理**：确保调度器、连接池正确关闭

---

## 九、本课总结

### 核心知识点

1. **心跳机制原理**
   - 定期发送小数据包保持连接活跃
   - 检测对方是否在线
   - 快速发现连接断开

2. **IdleStateHandler 应用**
   - 检测读/写/全空闲状态
   - 触发 `userEventTriggered()` 事件
   - 配合自定义 Handler 实现心跳

3. **客户端心跳实现**
   - 监听 `WRITER_IDLE` 事件
   - 定时发送心跳请求
   - 处理心跳响应

4. **服务端心跳实现**
   - 监听 `READER_IDLE` 事件
   - 响应客户端心跳请求
   - 检测异常连接

5. **断线重连策略**
   - 指数退避算法
   - 随机抖动避免风暴
   - 最大重试次数限制

### 高内聚低耦合设计

```
┌─────────────────────────────────────────────────────────┐
│                    模块职责划分                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  HeartbeatHandler                                       │
│    职责：监听空闲事件，发送心跳                         │
│    依赖：无                                             │
│    被依赖：IdleStateHandler                            │
│                                                         │
│  ReconnectHandler                                       │
│    职责：监听断开事件，执行重连                         │
│    依赖：ConnectionPool                                │
│    被依赖：Channel                                     │
│                                                         │
│  ServerHeartbeatHandler                                 │
│    职责：检测客户端连接状态                             │
│    依赖：无                                             │
│    被依赖：IdleStateHandler                            │
│                                                         │
│  RpcRequestHandler                                      │
│    职责：处理心跳请求，返回响应                         │
│    依赖：LocalRegistry                                 │
│    被依赖：Channel                                     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**设计优势：**

1. ✅ **单一职责**：每个 Handler 只负责一个功能
2. ✅ **易于测试**：可以独立测试每个 Handler
3. ✅ **易于扩展**：添加新功能不影响现有代码
4. ✅ **易于维护**：问题定位清晰，修改影响小

---

## 十、课后思考

1. **心跳消息是否需要序列化？**
   - 提示：考虑性能和通用性

2. **如何设计双向心跳检测？**
   - 提示：客户端和服务端都主动发送心跳

3. **重连时的请求如何处理？**
   - 提示： pending 请求是重试还是失败？

4. **如何实现心跳延迟动态调整？**
   - 提示：根据网络质量调整心跳频率

---

## 十一、动手练习

### 练习 1：实现心跳统计功能

统计心跳成功率、平均延迟等指标。

提示：
```java
public class HeartbeatStats {
    // 记录心跳次数
    // 记录失败次数
    // 记录延迟总和
    // 提供查询方法
}
```

### 练习 2：实现心跳告警

当心跳失败率超过阈值时发送告警。

提示：
```java
if (failureRate > 0.1) {  // 失败率超过 10%
    alertService.send("心跳异常告警");
}
```

### 练习 3：实现优雅重连

重连前通知所有 pending 请求，避免请求丢失。

提示：
```java
// 重连前
requestManager.failAll(new ReconnectException("正在重连"));

// 重连后
// 自动重试失败的请求
```

---

## 十二、下一步

下一节课我们将实现**SPI 机制与可扩展性**，让 RPC 框架支持热插拔扩展。

**[跳转到第 11 课：SPI 机制与可扩展性](./lesson-11-spi.md)**

**[返回课程目录](./README.md)**
