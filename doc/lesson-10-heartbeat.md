# 第 10 课：心跳检测与断线重连

## 学习目标

- 理解心跳机制的原理和作用
- 掌握 Netty 的 IdleStateHandler 使用
- 实现客户端自动重连机制
- 实现服务端连接管理
- 理解 TCP KeepAlive 与应用层心跳的区别

---

## 一、为什么需要心跳检测？

### 1.1 问题场景

在 RPC 系统中，客户端和服务端使用长连接通信。但是会遇到以下问题：

```
场景 1：客户端宕机
Client ─────────────> Server
  ↓ (崩溃、断电)
(连接断开，但 Server 不知道)
Server 仍然认为连接有效 ❌

场景 2：网络故障
Client ──X──X──X──> Server
      (网络中断)
(connection 未关闭，但无法通信)

场景 3：防火墙超时
Client ────────────> Server
       (30 分钟无数据)
Firewall 默默关闭连接
Client 和 Server 都不知道 ❌
```

**问题：**
- ❌ 无效连接占用资源
- ❌ 请求发送到死连接
- ❌ 无法及时发现对端是否存活

### 1.2 心跳机制解决方案

```
┌─────────────────────────────────────────────────────────┐
│                    心跳机制原理                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│   Client                           Server               │
│     │                                │                  │
│     │──── Heartbeat (Ping) ────────>│                  │
│     │                                │                  │
│     │<─── Heartbeat (Pong) ─────────│                  │
│     │                                │                  │
│     │ (每隔 30 秒发送一次)              │                  │
│     │                                │                  │
│     │──── Ping ────────────────────>│                  │
│     │                                │                  │
│     │<─── Pong ─────────────────────│                  │
│     │                                │                  │
│     ⋮                                ⋮                  │
│                                                         │
│   如果超过 90 秒没收到响应：                             │
│     × 判定连接失效                                        │
│     × 主动关闭连接                                        │
│     × 触发重连机制                                        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**心跳的作用：**
1. ✅ **检测连接有效性**：确认对端是否存活
2. ✅ **保持连接活跃**：防止防火墙因超时而关闭连接
3. ✅ **及时发现故障**：快速感知网络问题
4. ✅ **清理无效连接**：释放服务器资源

---

## 二、TCP KeepAlive vs 应用层心跳

### 2.1 TCP KeepAlive

TCP 协议自带的保活机制：

```java
// Java 中启用 TCP KeepAlive
Socket socket = new Socket();
socket.setKeepAlive(true);
```

**参数（操作系统级别）：**
- `tcp_keepalive_time`：空闲多久开始发送探测（默认 7200 秒=2 小时）
- `tcp_keepalive_intvl`：探测间隔（默认 75 秒）
- `tcp_keepalive_probes`：探测次数（默认 9 次）

**缺点：**
- ❌ 时间太长（2 小时后才开始探测）
- ❌ 不可配置（由操作系统控制）
- ❌ 无法自定义业务逻辑

### 2.2 应用层心跳

在应用层定时发送特殊消息：

```java
// 应用层心跳消息
class Heartbeat {
    String type = "HEARTBEAT";
    long timestamp = System.currentTimeMillis();
}
```

**优点：**
- ✅ 时间可控（可以设置为几秒、几十秒）
- ✅ 灵活定制（可以携带额外信息）
- ✅ 快速响应（超时后立即处理）
- ✅ 跨平台（不依赖操作系统）

**结论：** RPC 框架应该使用**应用层心跳**

---

## 三、Netty 的 IdleStateHandler

### 3.1 IdleStateHandler 介绍

Netty 提供了 `IdleStateHandler`来检测空闲连接：

```java
public class IdleStateHandler extends ChannelDuplexHandler {
    
    /**
     * @param readerIdleTime 读空闲时间（秒）
     * @param writerIdleTime 写空闲时间（秒）
     * @param allIdleTime 既没读也没写的空闲时间（秒）
     */
    public IdleStateHandler(int readerIdleTime, 
                           int writerIdleTime, 
                           int allIdleTime) {
        // ...
    }
}
```

**触发事件：**
- `ReaderIdle`: 超过指定时间没有读到数据
- `WriterIdle`: 超过指定时间没有写入数据
- `AllIdle`: 超过指定时间既没读也没写

### 3.2 工作原理

```
┌─────────────────────────────────────────────────────────┐
│           IdleStateHandler 工作流程                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  启动时创建定时器                                       │
│       ↓                                                 │
│  每次 channelRead() → 重置读定时器                      │
│  每次 write() → 重置写定时器                            │
│       ↓                                                 │
│  定时器超时 → 触发 userEventTriggered()                │
│       ↓                                                 │
│  捕获 IdleStateEvent                                    │
│       ↓                                                 │
│  执行相应处理（发送心跳、关闭连接等）                   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 四、实现心跳检测

### 4.1 定义心跳消息

在 RpcMessageType 中添加心跳类型：

```java
package com.rpc.protocol;

/**
 * RPC 消息类型
 */
public enum RpcMessageType {
    
    /**
     * 普通 RPC 请求
     */
    REQUEST(0x01),
    
    /**
     * RPC 响应
     */
    RESPONSE(0x02),
    
    /**
     * 心跳请求
     */
    HEARTBEAT_REQUEST(0x03),
    
    /**
     * 心跳响应
     */
    HEARTBEAT_RESPONSE(0x04);
    
    private final byte code;
    
    RpcMessageType(int code) {
        this.code = (byte) code;
    }
    
    public byte getCode() {
        return code;
    }
    
    public static RpcMessageType fromCode(byte code) {
        for (RpcMessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的消息类型：" + code);
    }
}
```

创建心跳消息类：

```java
package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 心跳消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeartbeat {
    
    /**
     * 时间戳
     */
    private long timestamp;
    
    /**
     * 附加信息（可选）
     */
    private String message = "PING";
    
    /**
     * 创建心跳请求
     */
    public static RpcHeartbeat request() {
        return new RpcHeartbeat(System.currentTimeMillis(), "PING");
    }
    
    /**
     * 创建心跳响应
     */
    public static RpcHeartbeat response() {
        return new RpcHeartbeat(System.currentTimeMillis(), "PONG");
    }
}
```

### 4.2 客户端心跳 Handler

```java
package com.rpc.transport.netty.client.handler;

import com.rpc.protocol.RpcHeartbeat;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端心跳处理器
 */
@Slf4j
public class ClientHeartbeatHandler extends ChannelInboundHandlerAdapter {
    
    // 重连次数
    private int retryCount = 0;
    
    // 最大重连次数
    private static final int MAX_RETRY_COUNT = 5;
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            if (event.state() == IdleState.WRITER_IDLE) {
                // 写空闲：发送心跳
                log.debug("写空闲，发送心跳");
                sendHeartbeat(ctx);
                
            } else if (event.state() == IdleState.READER_IDLE) {
                // 读空闲：可能服务端已断开
                log.warn("读空闲，可能服务端已断开连接");
                handleReaderIdle(ctx);
                
            } else if (event.state() == IdleState.ALL_IDLE) {
                // 完全空闲：也发送心跳保持连接
                log.debug("完全空闲，发送心跳保持连接");
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
            RpcHeartbeat heartbeat = RpcHeartbeat.request();
            
            // 构建心跳消息
            RpcMessage message = new RpcMessage();
            message.setMessageType(RpcMessageType.HEARTBEAT_REQUEST.getCode());
            message.setBody(heartbeat);
            
            ctx.writeAndFlush(message);
            log.debug("发送心跳：{}", heartbeat);
            
        } catch (Exception e) {
            log.error("发送心跳失败", e);
        }
    }
    
    /**
     * 处理读空闲（超时未收到数据）
     */
    private void handleReaderIdle(ChannelHandlerContext ctx) {
        retryCount++;
        
        if (retryCount <= MAX_RETRY_COUNT) {
            log.warn("读空闲，尝试发送心跳检测 [{}]/{}", retryCount, MAX_RETRY_COUNT);
            sendHeartbeat(ctx);
        } else {
            // 超过最大重试次数，关闭连接并重连
            log.error("超过最大重试次数，关闭连接");
            ctx.close();
            
            // 触发重连逻辑
            triggerReconnect(ctx);
        }
    }
    
    /**
     * 触发重连
     */
    private void triggerReconnect(ChannelHandlerContext ctx) {
        // 这里可以通知连接管理器进行重连
        // 具体实现在 RpcNettyClient 中
        log.info("准备重连...");
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("连接已断开");
        retryCount = 0; // 重置重连次数
    }
}
```

### 4.3 服务端心跳 Handler

```java
package com.rpc.transport.netty.server.handler;

import com.rpc.protocol.RpcHeartbeat;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务端心跳处理器
 */
@Slf4j
public class ServerHeartbeatHandler extends ChannelInboundHandlerAdapter {
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            if (event.state() == IdleState.READER_IDLE) {
                // 读空闲：客户端可能已断开
                log.warn("客户端读空闲，可能已断开：{}", ctx.channel().remoteAddress());
                
                // 主动发送心跳探测
                sendHeartbeat(ctx);
                
            } else if (event.state() == IdleState.ALL_IDLE) {
                // 完全空闲：关闭连接释放资源
                log.info("客户端完全空闲，关闭连接：{}", ctx.channel().remoteAddress());
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
    
    /**
     * 发送心跳响应
     */
    private void sendHeartbeat(ChannelHandlerContext ctx) {
        try {
            RpcHeartbeat heartbeat = RpcHeartbeat.response();
            
            RpcMessage message = new RpcMessage();
            message.setMessageType(RpcMessageType.HEARTBEAT_RESPONSE.getCode());
            message.setBody(heartbeat);
            
            ctx.writeAndFlush(message);
            log.debug("发送心跳响应：{}", heartbeat);
            
        } catch (Exception e) {
            log.error("发送心跳响应失败", e);
        }
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage msg) {
        // 如果是心跳请求，回复心跳响应
        if (msg.getMessageType() == RpcMessageType.HEARTBEAT_REQUEST.getCode()) {
            log.debug("收到客户端心跳：{}", msg.getBody());
            sendHeartbeat(ctx);
            return;
        }
        
        // 如果是心跳响应，说明连接正常
        if (msg.getMessageType() == RpcMessageType.HEARTBEAT_RESPONSE.getCode()) {
            log.debug("收到服务端心跳响应，连接正常");
            return;
        }
        
        // 其他消息交给下一个 handler 处理
        ctx.fireChannelRead(msg);
    }
}
```

---

## 五、整合到 Pipeline

### 5.1 修改客户端 Pipeline

```java
package com.rpc.transport.netty.client;

import com.rpc.config.RpcClientConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

public class RpcNettyClient {
    
    private void initPipeline(Bootstrap bootstrap) {
        bootstrap.handler(new ChannelInitializer<NioSocketChannel>() {
            @Override
            protected void initChannel(NioSocketChannel ch) {
                ch.pipeline()
                    // 日志处理器
                    .addLast("logging", new LoggingHandler(LogLevel.INFO))
                    
                    // 【关键】空闲检测
                    // 参数：读空闲 60 秒，写空闲 30 秒，完全空闲 60 秒
                    .addLast("idleStateHandler", 
                        new IdleStateHandler(60, 30, 60, TimeUnit.SECONDS))
                    
                    // 【关键】心跳处理器
                    .addLast("heartbeatHandler", new ClientHeartbeatHandler())
                    
                    // 编解码器
                    .addLast("encoder", new RpcProtocolEncoder())
                    .addLast("decoder", new RpcProtocolDecoder())
                    
                    // 业务处理器
                    .addLast("handler", new RpcClientHandler(requestManager));
            }
        });
    }
}
```

### 5.2 修改服务端 Pipeline

```java
package com.rpc.transport.netty.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

public class RpcNettyServer {
    
    private void initPipeline(ServerBootstrap bootstrap) {
        bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline()
                    // 日志处理器
                    .addLast("logging", new LoggingHandler(LogLevel.INFO))
                    
                    // 【关键】空闲检测
                    // 参数：读空闲 120 秒，写空闲 0 秒（服务端不主动发心跳），完全空闲 120 秒
                    .addLast("idleStateHandler", 
                        new IdleStateHandler(120, 0, 120, TimeUnit.SECONDS))
                    
                    // 【关键】心跳处理器
                    .addLast("heartbeatHandler", new ServerHeartbeatHandler())
                    
                    // 编解码器
                    .addLast("encoder", new RpcProtocolEncoder())
                    .addLast("decoder", new RpcProtocolDecoder())
                    
                    // 业务处理器
                    .addLast("handler", new RpcRequestHandler(localRegistry));
            }
        });
    }
}
```

---

## 六、实现断线重连

### 6.1 重连策略设计

```
┌─────────────────────────────────────────────────────────┐
│                    重连策略流程                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  连接断开                                                │
│     ↓                                                   │
│  等待一段时间（避免频繁重连）                           │
│     ↓                                                   │
│  尝试重连                                               │
│     ↓                                                   │
│  成功？──是──> 重连成功，重置计数器                     │
│     │                                                   │
│     否                                                  │
│     ↓                                                   │
│  增加重试次数                                           │
│     ↓                                                   │
│  超过最大次数？──是──> 放弃重连，报警                   │
│     │                                                   │
│     否                                                  │
│     ↓                                                   │
│  等待更长时间（指数退避）                               │
│     ↓                                                   │
│  返回"尝试重连"步骤                                     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 6.2 实现重连管理器

```java
package com.rpc.transport.netty.client.manager;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.*;

/**
 * 重连管理器
 */
@Slf4j
public class ReconnectManager {
    
    // 线程池
    private final ScheduledExecutorService executor = 
        Executors.newScheduledThreadPool(1);
    
    // Bootstrap
    private final Bootstrap bootstrap;
    
    // 服务地址
    private final InetSocketAddress remoteAddress;
    
    // 当前重连次数
    private int reconnectCount = 0;
    
    // 最大重连次数
    private final int maxReconnectTimes;
    
    // 基础重连间隔（毫秒）
    private final long baseDelay = 1000; // 1 秒
    
    // 最大重连间隔（毫秒）
    private final long maxDelay = 60000; // 60 秒
    
    // 是否正在重连
    private volatile boolean reconnecting = false;
    
    // 连接成功监听器
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    
    public ReconnectManager(Bootstrap bootstrap, 
                           InetSocketAddress remoteAddress,
                           int maxReconnectTimes) {
        this.bootstrap = bootstrap;
        this.remoteAddress = remoteAddress;
        this.maxReconnectTimes = maxReconnectTimes;
    }
    
    /**
     * 开始重连
     */
    public void startReconnect() {
        if (reconnecting) {
            log.warn("正在重连中，忽略本次请求");
            return;
        }
        
        reconnecting = true;
        scheduleReconnect(0);
    }
    
    /**
     * 调度重连任务
     */
    private void scheduleReconnect(int attempt) {
        long delay = calculateDelay(attempt);
        
        log.info("将在 {} 毫秒后尝试重连 [{}/{}]", 
            delay, attempt + 1, maxReconnectTimes);
        
        executor.schedule(() -> {
            try {
                doReconnect();
            } catch (Exception e) {
                log.error("重连失败", e);
                handleReconnectFailure(attempt);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 执行重连
     */
    private void doReconnect() {
        log.info("开始重连到 {}", remoteAddress);
        
        ChannelFuture future = bootstrap.connect(remoteAddress);
        
        future.addListener(f -> {
            if (f.isSuccess()) {
                // 重连成功
                log.info("重连成功！");
                reconnectCount = 0;
                reconnecting = false;
                connectedLatch.countDown();
                
            } else {
                // 重连失败
                log.warn("重连失败：{}", f.cause().getMessage());
                reconnectCount++;
                
                if (reconnectCount >= maxReconnectTimes) {
                    log.error("达到最大重连次数，放弃重连");
                    reconnecting = false;
                } else {
                    // 继续重连
                    scheduleReconnect(reconnectCount);
                }
            }
        });
    }
    
    /**
     * 计算延迟时间（指数退避）
     */
    private long calculateDelay(int attempt) {
        // 指数退避：1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s, ...
        long delay = baseDelay * (1L << attempt);
        return Math.min(delay, maxDelay);
    }
    
    /**
     * 处理重连失败
     */
    private void handleReconnectFailure(int attempt) {
        reconnectCount++;
        
        if (reconnectCount >= maxReconnectTimes) {
            log.error("达到最大重连次数 {}，放弃重连", maxReconnectTimes);
            reconnecting = false;
        } else {
            scheduleReconnect(reconnectCount);
        }
    }
    
    /**
     * 等待连接成功
     */
    public boolean awaitConnected(long timeout, TimeUnit unit) 
            throws InterruptedException {
        return connectedLatch.await(timeout, unit);
    }
    
    /**
     * 关闭重连管理器
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 重置状态（用于重新连接）
     */
    public void reset() {
        reconnectCount = 0;
        reconnecting = false;
    }
}
```

### 6.3 整合重连到客户端

```java
package com.rpc.transport.netty.client;

import com.rpc.config.RpcClientConfig;
import com.rpc.registry.ServiceRegistry;
import com.rpc.transport.netty.client.connection.RpcConnection;
import com.rpc.transport.netty.client.manager.ReconnectManager;
import com.rpc.transport.netty.client.manager.RequestManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 支持重连的 RPC 客户端
 */
@Slf4j
public class RpcNettyClient {
    
    // 重连管理器映射表（每个连接一个）
    private final Map<String, ReconnectManager> reconnectManagers = 
        new ConcurrentHashMap<>();
    
    // 最大重连次数
    private final int maxReconnectTimes;
    
    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.maxReconnectTimes = config.getMaxReconnectTimes();
        // ... 其他初始化代码
    }
    
    /**
     * 获取连接（支持重连）
     */
    public RpcConnection getConnection(String host, int port) throws Exception {
        String key = buildKey(host, port);
        
        RpcConnection connection = connectionMap.get(key);
        
        if (connection != null && connection.isActive()) {
            return connection;
        }
        
        // 连接不存在或已断开，创建新连接
        InetSocketAddress address = new InetSocketAddress(host, port);
        
        ChannelFuture future = bootstrap.connect(address).sync();
        Channel channel = future.channel();
        
        // 创建重连管理器
        ReconnectManager reconnectManager = new ReconnectManager(
            bootstrap, address, maxReconnectTimes);
        reconnectManagers.put(key, reconnectManager);
        
        // 添加连接关闭监听器
        channel.closeFuture().addListener(f -> {
            log.warn("连接已关闭，触发重连：{}", key);
            
            // 从连接池移除
            connectionMap.remove(key);
            
            // 启动重连
            reconnectManager.startReconnect();
        });
        
        RpcConnection newConnection = new RpcConnection(channel, host, port);
        connectionMap.put(key, newConnection);
        
        return newConnection;
    }
    
    /**
     * 手动触发重连
     */
    public void reconnect(String host, int port) {
        String key = buildKey(host, port);
        ReconnectManager manager = reconnectManagers.get(key);
        
        if (manager != null) {
            log.info("手动触发重连：{}", key);
            manager.reset();
            manager.startReconnect();
        } else {
            log.warn("未找到重连管理器：{}", key);
        }
    }
    
    /**
     * 关闭客户端
     */
    public void close() {
        // 关闭所有重连管理器
        for (ReconnectManager manager : reconnectManagers.values()) {
            manager.shutdown();
        }
        reconnectManagers.clear();
        
        // 关闭连接池
        connectionPool.close();
        
        // 关闭 EventLoopGroup
        eventLoopGroup.shutdownGracefully();
        
        // 关闭注册中心
        serviceRegistry.close();
    }
}
```

---

## 七、完整示例

### 7.1 配置示例

```java
// 客户端配置
RpcClientConfig clientConfig = RpcClientConfig.custom()
    .connectTimeout(5000)
    .readTimeout(10000)
    .maxReconnectTimes(5)  // 最大重连 5 次
    .loadBalancer(new RoundRobinLoadBalancer())
    .build();

// 服务端配置
RpcServerConfig serverConfig = RpcServerConfig.custom()
    .port(8080)
    .heartbeatInterval(30)  // 心跳间隔 30 秒
    .readTimeout(120)       // 读超时 120 秒
    .build();
```

### 7.2 测试心跳

```java
public class HeartbeatTest {
    
    @Test
    public void testHeartbeat() throws Exception {
        // 1. 启动服务端
        RpcNettyServer server = new RpcNettyServer(serverConfig, registry);
        server.start();
        
        // 2. 启动客户端
        RpcNettyClient client = new RpcNettyClient(clientConfig, registry);
        
        // 3. 等待一段时间，观察心跳日志
        Thread.sleep(120000); // 2 分钟
        
        // 4. 验证连接仍然有效
        HelloService proxy = proxyFactory.createProxy(HelloService.class);
        String result = proxy.sayHello("test");
        
        assertNotNull(result);
        
        // 5. 清理
        client.close();
        server.shutdown();
    }
    
    @Test
    public void testReconnect() throws Exception {
        // 1. 启动客户端和服务端
        // ...
        
        // 2. 模拟服务端宕机
        server.shutdown();
        
        // 3. 等待心跳超时
        Thread.sleep(70000); // 70 秒
        
        // 4. 观察客户端重连日志
        // 应该看到多次重连尝试
        
        // 5. 重新启动服务端
        server.start();
        
        // 6. 等待重连成功
        Thread.sleep(5000);
        
        // 7. 验证连接恢复
        String result = proxy.sayHello("test");
        assertNotNull(result);
    }
}
```

---

## 八、本课总结

### 核心知识点

1. **心跳机制的作用**
   - 检测连接有效性
   - 保持连接活跃
   - 及时发现故障

2. **TCP KeepAlive vs 应用层心跳**
   - TCP KeepAlive：时间长、不可控
   - 应用层心跳：灵活、快速、可定制

3. **IdleStateHandler 的使用**
   - 读空闲：超过时间未收到数据
   - 写空闲：超过时间未发送数据
   - 完全空闲：既没读也没写

4. **重连策略**
   - 指数退避：避免频繁重连
   - 最大重试次数：防止无限重连
   - 连接监听：自动触发重连

### 课后思考

1. 心跳间隔设置多少合适？太短或太长有什么问题？
2. 如何区分网络抖动和服务端真的宕机？
3. 重连时是否需要重新注册服务？
4. 如何实现优雅关闭（不触发重连）？

---

## 九、动手练习

### 练习 1：实现双向心跳

客户端和服务端都主动发送心跳，而不是只有客户端发送。

### 练习 2：添加重连回调

重连成功/失败时通知业务层。

提示：
```java
public interface ReconnectListener {
    void onReconnectSuccess();
    void onReconnectFailed(int retryCount);
}
```

### 练习 3：实现连接池预热

启动时预先建立一定数量的连接。

---

## 十、下一步

下一节课我们将学习**SPI 机制与可扩展性**，使框架支持插件化扩展。

**[跳转到第 11 课：SPI 机制与可扩展性](./lesson-11-spi.md)**
