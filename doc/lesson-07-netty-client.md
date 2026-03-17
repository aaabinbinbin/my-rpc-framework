# 第 7 课：Netty 客户端实现

## 学习目标

- 掌握 Netty 客户端的启动流程
- 学会管理 Channel 连接池
- 实现异步请求和 Future/Promise 模式
- 理解同步转异步的处理方式

---

## 一、RPC 客户端架构设计

### 1.1 客户端整体架构

```
┌─────────────────────────────────────────────────────────┐
│                   RPC Client                            │
├─────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────┐  │
│  │              Proxy Layer (代理层)                 │  │
│  │  - JDK/CGLIB 动态代理                             │  │
│  │  - 拦截方法调用                                    │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │           Request Manager (请求管理器)             │  │
│  │  - 生成请求 ID                                     │  │
│  │  - 存储 Pending 请求                                │  │
│  │  - 匹配响应                                        │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │            Netty Network Layer                    │  │
│  │  - Connection Pool (连接池)                       │  │
│  │  - Decoder/Encoder (编解码器)                     │  │
│  │  - Handler (处理器)                               │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 1.2 核心挑战

1. **同步转异步**：用户调用是同步的，但网络 IO 是异步的
2. **请求响应匹配**：如何匹配响应和请求？
3. **连接管理**：长连接 vs 短连接？连接池如何设计？
4. **超时处理**：如何处理长时间未响应的请求？

---

## 二、Future/Promise 模式

### 2.1 什么是 Future/Promise？

**Future**：代表一个异步操作的结果。  
**Promise**：可以设置结果的 Future。

```java
// Java 自带的 Future 示例
ExecutorService executor = Executors.newSingleThreadExecutor();

// 提交异步任务
Future<String> future = executor.submit(() -> {
    Thread.sleep(1000);
    return "Hello";
});

// 同步获取结果（阻塞）
String result = future.get();  // 阻塞等待结果

// 带超时的获取
String result = future.get(2, TimeUnit.SECONDS);
```

### 2.2 我们的实现方案

我们将使用 `CompletableFuture`（Java 8+）来实现异步转同步。

```java
import java.util.concurrent.CompletableFuture;

// 创建 CompletableFuture
CompletableFuture<String> future = new CompletableFuture<>();

// 异步线程中设置结果
new Thread(() -> {
    // 执行耗时操作
    String result = doSomething();
    // 设置结果（唤醒等待的线程）
    future.complete(result);
}).start();

// 主线程同步获取结果（可选）
try {
    String result = future.get(5, TimeUnit.SECONDS);  // 最多等 5 秒
    System.out.println("结果：" + result);
} catch (TimeoutException e) {
    System.out.println("超时了！");
}
```

---

## 三、请求管理器

### 3.1 待处理请求容器

```java
package com.rpc.client.manager;

import com.rpc.core.protocol.impl.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 请求管理器
 * 管理所有发出但未收到响应的请求
 */
@Slf4j
public class RequestManager {
    
    /**
     * 存储待处理的请求
     * key: requestId
     * value: CompletableFuture（用于接收响应）
     */
    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests 
        = new ConcurrentHashMap<>();
    
    /**
     * 添加新的请求
     * @param requestId 请求 ID
     * @return CompletableFuture
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        
        log.debug("添加请求：requestId={}", requestId);
        return future;
    }
    
    /**
     * 收到响应，完成 Future
     * @param response RPC 响应
     */
    public void completeResponse(RpcResponse response) {
        long requestId = response.getRequestId();
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        
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
        
        if (future != null) {
            future.completeExceptionally(cause);
            log.error("请求失败：requestId={}", requestId, cause);
        }
    }
    
    /**
     * 清理超时的请求
     * @param timeoutMs 超时时间（毫秒）
     */
    public void clearTimeoutRequests(long timeoutMs) {
        // TODO: 可以添加超时检查逻辑
        // 实际项目中可以使用定时任务或时间轮算法
    }
    
    /**
     * 获取待处理请求数量
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }
}
```

---

## 四、Netty 客户端处理器

### 4.1 客户端 Handler

```java
package com.rpc.client.netty.handler;

import com.rpc.client.manager.RequestManager;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.MessageType;
import com.rpc.core.protocol.impl.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
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
        // 1. 根据消息类型处理
        if (message.getHeader().getMessageType() == MessageType.RESPONSE) {
            handleResponse(message);
        } else if (message.getHeader().getMessageType() == MessageType.HEARTBEAT_RESPONSE) {
            log.debug("收到心跳响应");
        } else {
            log.warn("不支持的消息类型：{}", message.getHeader().getMessageType());
        }
    }
    
    /**
     * 处理响应
     */
    private void handleResponse(RpcMessage message) {
        RpcResponse response = (RpcResponse) message.getBody();
        
        // 通知请求管理器完成 Future
        requestManager.completeResponse(response);
    }
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            // 写空闲时，发送心跳
            if (event.state() == IdleState.WRITER_IDLE) {
                log.debug("发送心跳");
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
        // TODO: 实现心跳消息发送
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("已连接到服务器：{}", ctx.channel().remoteAddress());
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("与服务器断开连接：{}", ctx.channel().remoteAddress());
        
        // 失败所有待处理请求
        // TODO: 实现失败逻辑
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端异常", cause);
        ctx.close();
    }
}
```

---

## 五、连接池管理

### 5.1 单连接封装

```java
package com.rpc.client.connection;

import io.netty.channel.Channel;
import lombok.Data;

/**
 * 连接封装
 */
@Data
public class RpcConnection {
    
    /** Netty Channel */
    private Channel channel;
    
    /** 服务器地址 */
    private String host;
    
    /** 服务器端口 */
    private int port;
    
    /** 最后使用时间 */
    private long lastUseTime;
    
    /** 是否可用 */
    private boolean available;
    
    public RpcConnection(Channel channel, String host, int port) {
        this.channel = channel;
        this.host = host;
        this.port = port;
        this.lastUseTime = System.currentTimeMillis();
        this.available = true;
    }
    
    /**
     * 更新最后使用时间
     */
    public void updateLastUseTime() {
        this.lastUseTime = System.currentTimeMillis();
    }
    
    /**
     * 检查连接是否有效
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }
}
```

### 5.2 简单连接池

```java
package com.rpc.client.pool;

import com.rpc.client.connection.RpcConnection;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的连接池
 * 每个服务器地址维护一个连接
 */
@Slf4j
public class ConnectionPool {
    
    /**
     * 存储连接
     * key: serverAddress (host:port)
     * value: RpcConnection
     */
    private final Map<String, RpcConnection> connectionMap = new ConcurrentHashMap<>();
    
    private final Bootstrap bootstrap;
    
    public ConnectionPool(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }
    
    /**
     * 获取连接（如果没有则创建）
     */
    public RpcConnection getConnection(String host, int port) throws Exception {
        String key = buildKey(host, port);
        
        // 1. 尝试获取已有连接
        RpcConnection connection = connectionMap.get(key);
        
        if (connection != null && connection.isActive()) {
            log.debug("复用已有连接：{}", key);
            connection.updateLastUseTime();
            return connection;
        }
        
        // 2. 创建新连接
        log.info("创建新连接：{}", key);
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port)).sync();
        
        Channel channel = future.channel();
        RpcConnection newConnection = new RpcConnection(channel, host, port);
        
        connectionMap.put(key, newConnection);
        
        return newConnection;
    }
    
    /**
     * 关闭并移除连接
     */
    public void removeConnection(String host, int port) {
        String key = buildKey(host, port);
        RpcConnection connection = connectionMap.remove(key);
        
        if (connection != null) {
            connection.getChannel().close();
            log.info("连接已关闭：{}", key);
        }
    }
    
    /**
     * 关闭所有连接
     */
    public void closeAll() {
        for (RpcConnection connection : connectionMap.values()) {
            try {
                connection.getChannel().close().sync();
            } catch (Exception e) {
                log.error("关闭连接失败", e);
            }
        }
        connectionMap.clear();
        log.info("所有连接已关闭");
    }
    
    /**
     * 构建连接键
     */
    private String buildKey(String host, int port) {
        return host + ":" + port;
    }
    
    /**
     * 获取连接池大小
     */
    public int size() {
        return connectionMap.size();
    }
}
```

---

## 六、Netty 客户端主类

### 6.1 客户端实现

```java
package com.rpc.client.netty;

import com.rpc.client.connection.RpcConnection;
import com.rpc.client.manager.RequestManager;
import com.rpc.client.netty.handler.RpcClientHandler;
import com.rpc.client.pool.ConnectionPool;
import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.MessageType;
import com.rpc.core.protocol.codec.RpcProtocolDecoder;
import com.rpc.core.protocol.codec.RpcProtocolEncoder;
import com.rpc.core.protocol.impl.RpcRequest;
import com.rpc.core.protocol.impl.RpcResponse;
import com.rpc.core.serialize.SerializerFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 客户端
 */
@Slf4j
public class RpcNettyClient {
    
    private EventLoopGroup eventLoopGroup;
    private ConnectionPool connectionPool;
    private RequestManager requestManager;
    
    private int connectTimeout = 5000;  // 连接超时 5 秒
    private int readTimeout = 10000;     // 读取超时 10 秒
    
    public RpcNettyClient() {
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        
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
                           .addLast("idleStateHandler", 
                                   new IdleStateHandler(0, 30, 0, TimeUnit.SECONDS))
                           .addLast("decoder", new RpcProtocolDecoder())
                           .addLast("encoder", new RpcProtocolEncoder())
                           .addLast("handler", new RpcClientHandler(requestManager));
                     }
                 });
        
        this.connectionPool = new ConnectionPool(bootstrap);
    }
    
    /**
     * 发送 RPC 请求（同步方式）
     * @param rpcRequest RPC 请求对象
     * @return RPC 响应
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest) {
        return sendRequest(rpcRequest, "127.0.0.1", 8080);
    }
    
    /**
     * 发送 RPC 请求到指定服务器
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest, String host, int port) {
        try {
            // 1. 生成请求 ID
            long requestId = generateRequestId();
            rpcRequest.setRequestId(requestId);
            
            // 2. 创建 Future 用于接收响应
            CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);
            
            // 3. 获取连接
            RpcConnection connection = connectionPool.getConnection(host, port);
            
            // 4. 构建请求消息
            RpcHeader header = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                .messageType(MessageType.REQUEST)
                .reserved((byte) 0)
                .requestId(requestId)
                .build();
            
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(rpcRequest);
            
            // 5. 发送消息
            connection.getChannel().writeAndFlush(message).sync();
            log.debug("请求已发送：{}.{}", rpcRequest.getServiceName(), 
                     rpcRequest.getMethodName());
            
            // 6. 同步等待响应（带超时）
            RpcResponse response = future.get(readTimeout, TimeUnit.MILLISECONDS);
            
            // 7. 检查响应状态
            if (response.getCode() != 200) {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
            
            return response;
            
        } catch (Exception e) {
            log.error("发送请求失败", e);
            requestManager.failRequest(rpcRequest.getRequestId(), e);
            throw new RuntimeException("发送请求失败", e);
        }
    }
    
    /**
     * 异步发送请求
     */
    public CompletableFuture<RpcResponse> sendRequestAsync(
            RpcRequest rpcRequest, String host, int port) {
        
        try {
            // 1. 生成请求 ID
            long requestId = generateRequestId();
            rpcRequest.setRequestId(requestId);
            
            // 2. 创建 Future
            CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);
            
            // 3. 获取连接
            RpcConnection connection = connectionPool.getConnection(host, port);
            
            // 4. 构建消息
            RpcHeader header = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                .messageType(MessageType.REQUEST)
                .reserved((byte) 0)
                .requestId(requestId)
                .build();
            
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(rpcRequest);
            
            // 5. 异步发送
            connection.getChannel().writeAndFlush(message)
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        requestManager.failRequest(requestId, f.cause());
                    }
                });
            
            return future;
            
        } catch (Exception e) {
            log.error("异步发送请求失败", e);
            CompletableFuture<RpcResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }
    
    /**
     * 生成请求 ID
     */
    private long generateRequestId() {
        // 简单实现：使用时间戳
        return System.nanoTime();
    }
    
    /**
     * 关闭客户端
     */
    public void close() {
        log.info("正在关闭客户端...");
        
        if (connectionPool != null) {
            connectionPool.closeAll();
        }
        
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully()
                       .awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        
        log.info("客户端已关闭");
    }
}
```

---

## 七、整合动态代理

### 7.1 完整的 RPC 代理工厂

```java
package com.rpc.client.proxy;

import com.rpc.client.netty.RpcNettyClient;
import com.rpc.core.protocol.impl.RpcRequest;
import com.rpc.core.protocol.impl.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * RPC 代理工厂（完整版）
 */
@Slf4j
public class RpcProxyFactory {
    
    private static RpcNettyClient client;
    
    /**
     * 初始化客户端
     */
    public static void initClient(RpcNettyClient rpcClient) {
        client = rpcClient;
    }
    
    /**
     * 创建代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass) {
        return createProxy(serviceClass, "127.0.0.1", 8080);
    }
    
    /**
     * 创建代理对象（指定服务器地址）
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass, String host, int port) {
        
        return (T) Proxy.newProxyInstance(
            serviceClass.getClassLoader(),
            new Class<?>[]{serviceClass},
            new RpcInvocationHandler(serviceClass, host, port)
        );
    }
    
    /**
     * RPC 调用处理器
     */
    @Slf4j
    private static class RpcInvocationHandler implements InvocationHandler {
        
        private final Class<?> serviceClass;
        private final String host;
        private final int port;
        
        public RpcInvocationHandler(Class<?> serviceClass, String host, int port) {
            this.serviceClass = serviceClass;
            this.host = host;
            this.port = port;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 跳过 Object 类的方法
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            
            // 2. 构建 RPC 请求
            RpcRequest request = new RpcRequest();
            request.setServiceName(serviceClass.getName());
            request.setMethodName(method.getName());
            request.setParameterTypes(method.getParameterTypes());
            request.setParameters(args);
            request.setReturnType(method.getReturnType());
            
            log.info("准备调用：{}.{}", request.getServiceName(), 
                    request.getMethodName());
            
            // 3. 发送远程请求
            if (client == null) {
                throw new IllegalStateException("RPC 客户端未初始化");
            }
            
            RpcResponse response = client.sendRequest(request, host, port);
            
            // 4. 返回结果
            if (response.getCode() == 200) {
                return response.getData();
            } else {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
        }
    }
}
```

---

## 八、完整使用示例

### 8.1 客户端测试代码

```java
package com.rpc.example.consumer;

import com.rpc.client.netty.RpcNettyClient;
import com.rpc.client.proxy.RpcProxyFactory;
import com.rpc.example.api.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 消费者测试
 */
@Slf4j
public class RpcConsumerTest {
    
    public static void main(String[] args) {
        RpcNettyClient client = null;
        
        try {
            // 1. 创建并初始化客户端
            client = new RpcNettyClient();
            RpcProxyFactory.initClient(client);
            
            // 2. 创建代理
            HelloService service = RpcProxyFactory.createProxy(
                HelloService.class, 
                "127.0.0.1", 
                8080
            );
            
            // 3. 调用方法
            log.info("========== 测试 sayHello ==========");
            String result1 = service.sayHello("张三");
            log.info("结果：{}", result1);
            
            log.info("========== 测试 sayHi ==========");
            String result2 = service.sayHi("李四");
            log.info("结果：{}", result2);
            
            log.info("========== 测试 add ==========");
            Integer result3 = service.add(10, 20);
            log.info("结果：{}", result3);
            
            log.info("========== 所有测试完成 ==========");
            
        } catch (Exception e) {
            log.error("测试失败", e);
        } finally {
            // 4. 关闭客户端
            if (client != null) {
                client.close();
            }
        }
    }
}
```

### 8.2 运行步骤

**步骤 1**：先启动服务端（第 6 课的 `RpcProviderBootstrap`）

```
运行：com.rpc.example.provider.RpcProviderBootstrap.main()
输出：RPC 服务器启动成功，监听端口：8080
```

**步骤 2**：再启动客户端

```
运行：com.rpc.example.consumer.RpcConsumerTest.main()
输出：
========== 测试 sayHello ==========
准备调用：com.rpc.example.api.HelloService.sayHello
信息：收到 RPC 请求：com.rpc.example.api.HelloService.sayHello
信息：收到 sayHello 请求：张三
结果：Hello, 张三!
... (其他测试结果)
```

---

## 九、本课总结

### 核心知识点

1. **Future/Promise 模式**
   - 将异步操作转换为同步结果
   - 使用 `CompletableFuture` 实现

2. **请求管理器**
   - 维护请求 ID 与 Future 的映射
   - 收到响应时完成对应的 Future

3. **连接池管理**
   - 每个服务器地址维护一个长连接
   - 自动重连机制

4. **Netty 客户端**
   - Bootstrap 配置
   - 编解码器配置
   - 心跳检测

5. **同步转异步**
   - 用户调用是同步的
   - 底层网络 IO 是异步的
   - 通过 Future 模式桥接

### 课后思考

1. 为什么要用连接池而不是每次创建新连接？
2. 如果服务端宕机，客户端应该如何处理？
3. 如何实现请求超时机制？
4. 如果要支持批量请求，应该如何修改？

---

## 十、动手练习

### 练习 1：实现连接健康检查

定期 ping 服务器，如果连接失效则自动重连。

### 练习 2：实现请求超时

为每个请求添加超时时间，超时后主动取消请求。

提示：
```java
scheduledExecutor.schedule(() -> {
    requestManager.failRequest(requestId, 
        new TimeoutException("请求超时"));
}, timeout, TimeUnit.MILLISECONDS);
```

### 练习 3：实现连接池大小限制

修改连接池，使其支持最大连接数限制。

---

## 十一、下一步

下一节课我们将实现**服务注册与发现**，使用 ZooKeeper 作为注册中心。

**[跳转到第 8 课：服务注册与发现](./lesson-08-service-registry.md)**
