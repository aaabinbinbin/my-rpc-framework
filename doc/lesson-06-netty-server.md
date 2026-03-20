# 第 6 课：Netty 服务端实现

## 学习目标

- 掌握 Netty 服务端的启动流程
- 学会配置 ChannelPipeline
- 实现 RPC 请求处理器
- 理解如何反射调用服务方法

---

## 一、RPC 服务端架构设计

### 1.1 服务端整体架构

```
┌─────────────────────────────────────────────────────────┐
│                   RPC Server                            │
├─────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────┐  │
│  │            Netty Network Layer                    │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌───────────┐ │  │
│  │  │  Decoder    │→ │   Encoder   │← │  Handler  │ │  │
│  │  └─────────────┘  └─────────────┘  └───────────┘ │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │              RPC Core Layer                       │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌───────────┐ │  │
│  │  │  Serializer │  │  Protocol   │  │  Thread   │ │  │
│  │  └─────────────┘  └─────────────┘  └───────────┘ │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │             Service Registry                      │  │
│  │  ┌─────────────────────────────────────────────┐  │  │
│  │  │  LocalRegistry (本地服务注册表)              │  │  │
│  │  │  - Map<String, Object> serviceMap          │  │  │
│  │  └─────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 1.2 核心组件

1. **网络层**：Netty 服务器，负责接收连接和收发数据
2. **协议层**：编解码器，处理 RPC 协议
3. **业务层**：请求处理器，反射调用服务方法
4. **注册表**：本地服务映射，存储服务实例

---

## 二、Netty Pipeline 处理器链路详解

### 2.1 Pipeline 的基本结构

```
┌─────────────────────────────────────────────────────────┐
│                    ChannelPipeline                       │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │ Handler 1│→ │ Handler 2│→ │ Handler 3│→ │ Handler 4│ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │
│       ↑                                      ↑          │
│     Head                                    Tail        │
│   (入站起点)                              (出站到点)      │
└─────────────────────────────────────────────────────────┘
```

**关键概念：**
- **HeadContext**：pipeline 的头部，连接实际的 Channel（Socket）
- **TailContext**：pipeline 的尾部，默认实现
- **Handler**：你添加的各种处理器

### 2.2 两种事件流：入站 vs 出站

#### **入站事件（Inbound）** - 读数据

```
网络数据 → SocketChannel.read()
    ↓
HeadContext.fireChannelRead()  ← 从头部开始
    ↓
Handler 1.channelRead()
    ↓
Handler 2.channelRead()
    ↓
Handler 3.channelRead()
    ↓
TailContext.channelRead()  ← 到尾端结束
```

**触发时机：**
- 从网络读取数据时
- 调用 `ctx.fireChannelRead(msg)` 时

#### **出站事件（Outbound）** - 写数据

```
应用层调用 writeAndFlush()
    ↓
当前 Handler
    ↓
前一个 Handler.write()  ← 向前传递！
    ↓
再前一个 Handler.write()
    ↓
HeadContext.write()  ← 最终写入网络
    ↓
SocketChannel.write()
```

**关键规则：**
- 当你从某个 handler 调用 `writeAndFlush()` 时，消息会**向 pipeline 的前面传递**（向 HeadContext 方向）
- 所有在这个 handler **之前**添加的 handler（使用 `addLast` 时就是排在它前面的）都会被触发
- 所有在这个 handler **之后**添加的 handler **不会被触发**

### 2.3 addLast() 方法的玄机

```java
// 实际效果：
// 第 1 次 addLast("A") → [A]
// 第 2 次 addLast("B") → [A, B]
// 第 3 次 addLast("C") → [A, B, C]
// 第 4 次 addLast("D") → [A, B, C, D]
```

**重要结论：**
- `addLast` 是**追加到链表末尾**
- 先添加的在前面，后添加的在后面
- **入站从前往后，出站从后往前！**

### 2.4 你的 RPC Pipeline 执行流程

**配置示例：**

```java
ch.pipeline()
    .addLast("idleStateHandler", new IdleStateHandler(...))  // 1
    .addLast("decoder", new RpcProtocolDecoder())            // 2
    .addLast("encoder", new RpcProtocolEncoder())            // 3
    .addLast("handler", new RpcRequestHandler(localRegistry)); // 4
```

**Pipeline 的实际结构：**

```
[idleStateHandler] → [decoder] → [encoder] → [handler]
       ↓                ↓           ↓           ↓
     第 1 个             第 2 个       第 3 个      第 4 个
```

**入站流程（读请求）：**

```
客户端发送数据
    ↓
SocketChannel.read() → ByteBuffer
    ↓
【HeadContext.fireChannelRead()】← 入站起点
    ↓
1️⃣ idleStateHandler.channelRead()
   - 检查连接是否空闲
   - 调用 ctx.fireChannelRead(msg) 传递
    ↓
2️⃣ decoder.channelRead() (RpcProtocolDecoder)
   - ByteToMessageDecoder 类型
   - 将 ByteBuffer 解码成 RpcMessage
   - 调用 ctx.fireChannelRead(rpcMessage) 传递
    ↓
3️⃣ encoder.channelRead() (RpcProtocolEncoder)
   - ⚠️ 注意：它是 MessageToByteEncoder
   - channelRead() 默认实现：ctx.fireChannelRead(msg)
   - 【直接传递，不处理】← 因为它只处理出站
    ↓
4️⃣ handler.channelRead() (RpcRequestHandler)
   - ✅ 在这里！处理 RpcMessage
   - 调用 handleRequest()
   - 业务逻辑执行
    ↓
【TailContext.channelRead()】← 入站终点
```

**出站流程（写响应）- 正确配置：**

```java
// ✅ 正确配置：encoder 在 handler 前面
.addLast("decoder", new RpcProtocolDecoder())
.addLast("encoder", new RpcProtocolEncoder())  // 移到 handler 前面
.addLast("handler", new RpcRequestHandler(localRegistry));
```

```
handler.handleRequest() 完成
    ↓
调用 sendMessage()
    ↓
ctx.writeAndFlush(responseMessage)
    ↓
【开始向前传递（向 Head 方向）】
    ↓
前一个 handler: encoder
    ↓
✅ encoder.write(ctx, responseMessage, promise)
   - MessageToByteEncoder 拦截
   - 检查类型：RpcMessage ✓
   - 调用 encode() 编码成 ByteBuf
   - 调用 ctx.write(byteBuf, promise) 继续传递
    ↓
再前一个 handler: decoder
    ↓
decoder.write(byteBuf, promise)
   - 默认实现：ctx.write(msg, promise)
   - 直接传递 ByteBuf
    ↓
到达 HeadContext.write(ByteBuf)
    ↓
检查消息类型：ByteBuf ✓
    ↓
调用 SocketChannel.write(ByteBuf)
    ↓
✅ 成功发送到网络！
```

### 2.5 常见错误配置

**❌ 错误配置：encoder 在最后**

```java
.addLast("decoder", new RpcProtocolDecoder())
.addLast("handler", new RpcRequestHandler(localRegistry))
.addLast("encoder", new RpcProtocolEncoder()); // 在 handler 后面
```

**问题分析：**

```
Pipeline: [decoder] → [handler] → [encoder]
                              ↑
                    用 addLast 加在最后
                    
当 handler 调用 writeAndFlush() 时：
- 消息向前传递（向 Head 方向）
- encoder 在 handler 的"后面"（按 addLast 顺序）
- 所以根本不会经过 encoder！
```

**错误执行流程：**

```
handler.handleRequest() 完成
    ↓
调用 sendMessage()
    ↓
ctx.writeAndFlush(responseMessage)
    ↓
【开始向前传递（向 Head 方向）】
    ↓
前一个 handler 是谁？
    ↓
❌ 没有前一个了！因为 handler 是最后一个 addLast 的
    ↓
直接到达 HeadContext.write()
    ↓
检查消息类型：RpcMessage
    ↓
💥 报错：unsupported message type: RpcMessage (expected: ByteBuf, FileRegion)
```

### 2.6 核心规则总结

**规则 1：addLast 的顺序决定位置**

```java
pipeline.addLast("A").addLast("B").addLast("C");

// 实际顺序：[A] → [B] → [C]
// A 在最前，C 在最后
```

**规则 2：入站从前往后**

```
入站：Head → A → B → C → Tail
```

**规则 3：出站从后往前**

```
出站：当前 Handler → 前一个 → 再前一个 → ... → Head
```

**规则 4：只有前面的 handler 才能被出站触发**

```java
pipeline.addLast("A").addLast("B").addLast("C");

// 如果在 B 中调用 writeAndFlush():
// 会经过：B → A → Head
// 不会经过：C（因为 C 在 B 后面）
```

**规则 5：编码器必须在处理器前面**

```java
// ✅ 正确
.addLast("encoder", new Encoder())
.addLast("handler", new Handler())

// ❌ 错误
.addLast("handler", new Handler())
.addLast("encoder", new Encoder())  // handler 的 write 不会经过 encoder
```

### 2.7 记忆口诀

> **"入站从前向后走，出站从后往回传。**  
> **addLast 往后追加，编码器要放前面。"**

### 2.8 实战检验清单

检查你的 Pipeline 配置是否正确：

```java
ch.pipeline()
    .addLast("idleStateHandler", new IdleStateHandler(...))  // 1
    .addLast("decoder", new RpcProtocolDecoder())            // 2
    .addLast("encoder", new RpcProtocolEncoder())            // 3 ← 在 handler 前面 ✓
    .addLast("handler", new RpcRequestHandler(localRegistry)); // 4 ← 最后一个
```

**验证问题：**

**Q1:** handler 调用 `writeAndFlush()` 时，消息向哪个方向传递？

**A1:** 向前（向 Head 方向）

**Q2:** 会经过哪些 handler？

**A2:** handler(4) → encoder(3) → decoder(2) → idleStateHandler(1) → Head

**Q3:** encoder 能被触发吗？

**A3:** ✅ 能！因为它在 handler 前面

---

## 三、ChannelInboundHandlerAdapter vs SimpleChannelInboundHandler

在实现 RPC 请求处理器时，我们有两个选择：`ChannelInboundHandlerAdapter` 和`SimpleChannelInboundHandler`。它们有什么区别？应该如何选择？

### 3.1 继承关系对比

```
ChannelHandler (接口)
    ↓
ChannelInboundHandler (接口)
    ↓
┌─────────────────────────────────────┐
│                                     │
↓                                     ↓
ChannelInboundHandlerAdapter    SimpleChannelInboundHandler<I>
    ↓                                     ↓
基础适配器                          自动释放 + 类型安全
```

### 3.2 核心区别对比表

| 特性 | ChannelInboundHandlerAdapter | SimpleChannelInboundHandler |
|------|------------------------------|-----------------------------|
| **继承自** | 直接实现 ChannelInboundHandler | 继承自 ChannelInboundHandlerAdapter |
| **泛型** | 无泛型，接收 Object | 有泛型 `<I>`，指定输入类型 |
| **方法** | `channelRead(ctx, msg)` | `channelRead0(ctx, msg)` |
| **自动释放** | ❌ 不自动释放 | ✅ 自动释放入站消息 |
| **灵活性** | ⭐⭐⭐⭐⭐ 高 | ⭐⭐⭐ 中 |
| **代码量** | 需要手动类型检查 | 更简洁 |

### 3.3 SimpleChannelInboundHandler 的内部实现

```java
// SimpleChannelInboundHandler 简化版源码
public abstract class SimpleChannelInboundHandler<I> extends ChannelInboundHandlerAdapter {
    private final TypeParameterMatcher matcher;
    private boolean autoRelease = true;  // 默认开启自动释放

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean released = false;
        try {
            // 1. 检查消息类型是否匹配泛型
            if (acceptInboundMessage(msg)) {
                // 2. 类型匹配，调用子类的 channelRead0
                channelRead0(ctx, (I) msg);
                released = true;  // 标记已处理
                
                // 3. 【关键】如果开启自动释放，处理完立即释放
                if (autoRelease) {
                    ReferenceCountUtil.release(msg);  // ← 自动释放内存
                }
            } else {
                // 4. 类型不匹配，传递给下一个 handler
                ctx.fireChannelRead(msg);
            }
        } catch (Exception e) {
            throw e;
        } finally {
            // 5. 如果没有自动释放且没有传递下去，手动释放
            if (!released && !ctx.fireChannelRead(msg)) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    protected abstract void channelRead0(ChannelHandlerContext ctx, I msg) throws Exception;
}
```

**关键点：**
- ✅ 自动进行类型检查（基于泛型）
- ✅ 自动释放入站消息（避免内存泄漏）
- ⚠️ 处理完后消息被释放，无法继续使用

### 3.4 ChannelInboundHandlerAdapter 的实现

```java
// ChannelInboundHandlerAdapter 简化版源码
public class ChannelInboundHandlerAdapter extends ChannelHandlerAdapter implements ChannelInboundHandler {
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 【关键】直接传递给下一个 handler，不做任何处理
        ctx.fireChannelRead(msg);
    }
    
    // 其他事件方法（都是空实现，由子类重写）
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
    }
    
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelReadComplete();
    }
}
```

**关键点：**
- ✅ 完全灵活，可以自定义所有逻辑
- ✅ 可以决定是否释放消息
- ✅ 可以决定是否传递给下一个 handler
- ⚠️ 需要手动管理内存和资源

### 3.5 实际使用对比

#### **场景 1：使用 SimpleChannelInboundHandler**

```java
public class RpcRequestHandler extends SimpleChannelInboundHandler<RpcMessage> {
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) throws Exception {
        // ✅ 优点：
        // 1. 不需要类型检查，msg 已经是 RpcMessage
        // 2. 不需要手动释放，处理完自动释放
        
        RpcHeader header = message.getHeader();
        handleRequest(ctx, message);
        
        // ⚠️ 注意：此时 message 已经被自动释放了
        // ReferenceCountUtil.release(message); ← 框架自动调用
    }
}
```

**执行流程：**
```
收到消息 (RpcMessage)
    ↓
channelRead() 内部检查类型 ✓
    ↓
调用 channelRead0(ctx, rpcMessage)
    ↓
你的业务逻辑
    ↓
【自动释放】ReferenceCountUtil.release(message) ← 自动执行
    ↓
结束
```

#### **场景 2：使用 ChannelInboundHandlerAdapter**

```java
public class RpcRequestHandler extends ChannelInboundHandlerAdapter {
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // ⚠️ 需要手动类型检查
        if (!(msg instanceof RpcMessage)) {
            ctx.fireChannelRead(msg);  // 类型不对，传递下去
            return;
        }
        
        RpcMessage message = (RpcMessage) msg;
        
        // 业务逻辑
        handleRequest(ctx, message);
        
        // ✅ 优点：
        // 1. 可以决定是否释放消息
        // 2. 可以决定是否传递给下一个 handler
        // 3. 可以在处理后继续使用 message
        
        // 如果需要传递给下一个入站 handler
        ctx.fireChannelRead(msg);
        
        // 或者手动释放
        // ReferenceCountUtil.release(msg);
    }
}
```

**执行流程：**
```
收到消息 (Object)
    ↓
channelRead(ctx, msg)
    ↓
手动类型检查 (instanceof)
    ↓
强制转换 (RpcMessage)
    ↓
你的业务逻辑
    ↓
【手动决定】是否释放？是否传递？← 你说了算
```

### 3.6 内存管理对比

#### **SimpleChannelInboundHandler - 自动释放**

```java
extends SimpleChannelInboundHandler<RpcMessage>

channelRead0(ctx, message) {
    // message 可以使用
    
    handleRequest(message);
    
    // 方法结束时，message 自动被释放
    // ReferenceCountUtil.release(message); ← 框架自动调用
}
```

#### **ChannelInboundHandlerAdapter - 手动管理**

```java
extends ChannelInboundHandlerAdapter

channelRead(ctx, msg) {
    RpcMessage message = (RpcMessage) msg;
    
    // message 可以使用
    
    handleRequest(message);
    
    // 方法结束时，message 不会被自动释放
    // 你需要自己决定：
    // 1. 传递给下一个 handler: ctx.fireChannelRead(msg);
    // 2. 自己释放：ReferenceCountUtil.release(msg);
    // 3. 不释放（很少见）
}
```

### 3.7 什么时候用哪个？

| 场景 | 推荐 | 原因 |
|------|------|------|
| 只需要读取消息，不需要传递 | SimpleChannelInboundHandler | 自动释放，省心 |
| 需要转发消息给其他 handler | ChannelInboundHandlerAdapter | 可以控制是否传递 |
| 需要多次使用消息对象 | ChannelInboundHandlerAdapter | 不会过早释放 |
| 处理出站消息的编码 | ChannelInboundHandlerAdapter | 更好的控制力 |
| 简单的入站消息处理 | SimpleChannelInboundHandler | 代码简洁 |

### 3.8 在 RPC 项目中的选择

#### ❌ **为什么 SimpleChannelInboundHandler 不适合 RPC Handler？**

```java
public class RpcRequestHandler extends SimpleChannelInboundHandler<RpcMessage> {
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        // 1. 处理请求
        handleRequest(ctx, message);
        
        // 2. 构建响应
        RpcMessage responseMessage = buildResponse(message);
        
        // 3. 发送响应
        ctx.writeAndFlush(responseMessage);  // ← 出站流程
        
        // ⚠️ 问题：此时 message 已经被自动释放了
        // 虽然这对当前代码没影响，但失去了灵活性
    }
}
```

**潜在问题：**
- 入站消息在处理完后立即被释放
- 如果后续逻辑还需要访问消息对象，就会出问题
- 在某些复杂的 Netty 版本或配置下，可能会影响出站流程

#### ✅ **为什么 ChannelInboundHandlerAdapter 更适合 RPC Handler？**

```java
public class RpcRequestHandler extends ChannelInboundHandlerAdapter {
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 1. 类型检查
        if (!(msg instanceof RpcMessage)) {
            ctx.fireChannelRead(msg);
            return;
        }
        
        RpcMessage message = (RpcMessage) msg;
        
        // 2. 处理请求（message 还在，可以随时使用）
        handleRequest(ctx, message);
        
        // 3. 构建响应并发送
        RpcMessage responseMessage = buildResponse(message);
        ctx.writeAndFlush(responseMessage);  // ← 走 pipeline 的出站流程
        
        // 4. 手动释放（如果需要）
        // ReferenceCountUtil.release(msg);
    }
}
```

**优势：**
- ✅ 完全控制消息的生命周期
- ✅ 可以在处理后继续使用消息对象
- ✅ 更好地与 Netty Pipeline 的出站流程集成
- ✅ 避免因自动释放导致的潜在问题

### 3.9 总结与建议

**对于 RPC 请求处理器，推荐使用 `ChannelInboundHandlerAdapter`，原因：**

1. **更好的控制力**：可以完全控制消息的释放时机
2. **更灵活**：可以根据需要决定是否传递给下一个 handler
3. **避免陷阱**：不会因为自动释放而导致意外问题
4. **适合复杂场景**：RPC 处理涉及请求解析、反射调用、响应构建等多个步骤，需要更好的控制力

**但是，`SimpleChannelInboundHandler` 也有其适用场景：**
- 简单的日志记录 handler
- 只需要读取一次的消息处理
- 不需要转发或复杂处理的场景

**记忆口诀：**
> "简单就用 Simple，复杂就用 Adapter。**
> **自动释放虽省心，手动控制更灵活。"

---

## 四、本地服务注册表

在启动 Netty 服务器之前，我们先实现一个本地服务注册表。

### 4.1 服务注册表接口

```java
package com.rpc.core.registry;

import java.util.Map;

/**
 * 本地服务注册表
 * 用于存储和管理本地服务实例
 */
public interface LocalRegistry {
    
    /**
     * 注册服务
     * @param serviceName 服务名称（接口全限定名）
     * @param serviceImpl 服务实现类
     */
    void register(String serviceName, Class<?> serviceImpl);
    
    /**
     * 获取服务实现类
     * @param serviceName 服务名称
     * @return 服务实现类
     */
    Class<?> getService(String serviceName);
    
    /**
     * 注销服务
     * @param serviceName 服务名称
     */
    void unregister(String serviceName);
    
    /**
     * 检查服务是否已注册
     */
    boolean contains(String serviceName);
}
```

### 4.2 服务注册表实现

```java
package com.rpc.core.registry.impl;

import com.rpc.core.registry.LocalRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地服务注册表实现
 * 使用 ConcurrentHashMap 保证线程安全
 */
@Slf4j
public class LocalRegistryImpl implements LocalRegistry {
    
    /**
     * 存储服务映射
     * key: 服务名称（接口全限定名）
     * value: 服务实现类
     */
    private static final Map<String, Class<?>> SERVICE_MAP = new ConcurrentHashMap<>();
    
    @Override
    public void register(String serviceName, Class<?> serviceImpl) {
        if (serviceName == null || serviceName.isEmpty()) {
            throw new IllegalArgumentException("服务名称不能为空");
        }
        if (serviceImpl == null) {
            throw new IllegalArgumentException("服务实现类不能为空");
        }
        
        SERVICE_MAP.put(serviceName, serviceImpl);
        log.info("服务注册成功：{} -> {}", serviceName, serviceImpl.getName());
    }
    
    @Override
    public Class<?> getService(String serviceName) {
        Class<?> serviceClass = SERVICE_MAP.get(serviceName);
        if (serviceClass == null) {
            log.error("服务未找到：{}", serviceName);
            throw new RuntimeException("服务未找到：" + serviceName);
        }
        return serviceClass;
    }
    
    @Override
    public void unregister(String serviceName) {
        SERVICE_MAP.remove(serviceName);
        log.info("服务注销成功：{}", serviceName);
    }
    
    @Override
    public boolean contains(String serviceName) {
        return SERVICE_MAP.containsKey(serviceName);
    }
}
```

---

## 五、RPC 请求处理器

这是服务端的核心业务逻辑处理器。

### 5.1 处理器实现

```java
package com.rpc.server.netty.handler;

import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.MessageType;
import com.rpc.core.protocol.impl.RpcRequest;
import com.rpc.core.protocol.impl.RpcResponse;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.serialize.Serializer;
import com.rpc.core.serialize.SerializerFactory;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * RPC 请求处理器
 * 处理客户端的远程调用请求
 */
@Slf4j
public class RpcRequestHandler extends SimpleChannelInboundHandler<RpcMessage> {
    
    private final LocalRegistry localRegistry;
    
    public RpcRequestHandler(LocalRegistry localRegistry) {
        this.localRegistry = localRegistry;
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        RpcHeader header = message.getHeader();
        
        // 1. 根据消息类型处理
        if (header.getMessageType() == MessageType.REQUEST) {
            handleRequest(ctx, message);
        } else if (header.getMessageType() == MessageType.HEARTBEAT_REQUEST) {
            handleHeartbeat(ctx, message);
        } else {
            log.warn("不支持的消息类型：{}", header.getMessageType());
        }
    }
    
    /**
     * 处理 RPC 请求
     */
    private void handleRequest(ChannelHandlerContext ctx, RpcMessage requestMessage) {
        RpcHeader requestHeader = requestMessage.getHeader();
        RpcRequest rpcRequest = (RpcRequest) requestMessage.getBody();
        
        log.info("收到 RPC 请求：{}.{}", 
                rpcRequest.getServiceName(), rpcRequest.getMethodName());
        
        RpcResponse rpcResponse;
        
        try {
            // 2. 获取服务实现类
            Class<?> serviceClass = localRegistry.getService(rpcRequest.getServiceName());
            
            // 3. 创建服务实例（实际应该用单例或 Spring 管理）
            Object serviceBean = serviceClass.getDeclaredConstructor().newInstance();
            
            // 4. 获取方法
            Method method = serviceClass.getMethod(
                rpcRequest.getMethodName(),
                rpcRequest.getParameterTypes()
            );
            
            // 5. 反射调用方法
            Object result = method.invoke(serviceBean, rpcRequest.getParameters());
            
            // 6. 构建成功响应
            rpcResponse = RpcResponse.success(result, rpcRequest.getRequestId());
            log.debug("RPC 调用成功：{}", result);
            
        } catch (Exception e) {
            log.error("RPC 调用失败", e);
            // 7. 构建失败响应
            rpcResponse = RpcResponse.fail(500, e.getMessage(), 
                                          rpcRequest.getRequestId());
        }
        
        // 8. 发送响应
        sendMessage(ctx, rpcResponse, requestHeader);
    }
    
    /**
     * 处理心跳请求
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, RpcMessage requestMessage) {
        log.debug("收到心跳请求");
        
        RpcResponse heartbeatResponse = RpcResponse.success(
            "PONG", 
            requestMessage.getHeader().getRequestId()
        );
        
        sendMessage(ctx, heartbeatResponse, requestMessage.getHeader());
    }
    
    /**
     * 发送响应消息
     */
    private void sendMessage(ChannelHandlerContext ctx, Object body, RpcHeader requestHeader) {
        // 1. 构建响应头
        RpcHeader responseHeader = RpcHeader.builder()
            .magicNumber(RpcHeader.MAGIC_NUMBER)
            .version(RpcHeader.VERSION)
            .serializerType(requestHeader.getSerializerType())
            .messageType(MessageType.RESPONSE)
            .reserved((byte) 0)
            .requestId(requestHeader.getRequestId())
            .build();
        
        // 2. 构建响应消息
        RpcMessage responseMessage = new RpcMessage();
        responseMessage.setHeader(responseHeader);
        responseMessage.setBody(body);
        
        // 3. 发送消息
        ctx.writeAndFlush(responseMessage)
           .addListener((ChannelFutureListener) future -> {
               if (!future.isSuccess()) {
                   log.error("发送响应失败", future.cause());
               }
           });
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("处理器异常", cause);
        ctx.close();
    }
}
```

---

## 五、Netty 服务端启动器

### 5.1 服务端配置类

```java
package com.rpc.server.config;

import lombok.Data;

/**
 * RPC 服务端配置
 */
@Data
public class RpcServerConfig {
    
    /** 服务器端口 */
    private int port = 8080;
    
    /** Boss 线程数 */
    private int bossThreads = 1;
    
    /** Worker 线程数 */
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
    
    /** 序列化器类型 */
    private byte serializerType = 1;  // 默认 Kryo
    
    /** 优雅关闭超时时间（秒） */
    private int shutdownTimeout = 10;
    
    // Builder 模式
    public static RpcServerConfig custom() {
        return new RpcServerConfig();
    }
    
    public RpcServerConfig port(int port) {
        this.port = port;
        return this;
    }
    
    public RpcServerConfig bossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
        return this;
    }
    
    public RpcServerConfig workerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }
    
    public RpcServerConfig serializerType(byte serializerType) {
        this.serializerType = serializerType;
        return this;
    }
}
```

### 5.2 Netty 服务端主类

> 💡 **注意 Pipeline 配置顺序**：确保 `encoder` 在 `handler` 前面，这样 `handler` 调用 `writeAndFlush()` 时才能正确触发编码器。

```java
package com.rpc.server.netty;

import com.rpc.core.protocol.codec.RpcProtocolDecoder;
import com.rpc.core.protocol.codec.RpcProtocolEncoder;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.impl.LocalRegistryImpl;
import com.rpc.server.config.RpcServerConfig;
import com.rpc.server.netty.handler.RpcRequestHandler;
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

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 服务端
 */
@Slf4j
public class RpcNettyServer {
    
    private final RpcServerConfig config;
    private final LocalRegistry localRegistry;
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    
    public RpcNettyServer() {
        this(new RpcServerConfig());
    }
    
    public RpcNettyServer(RpcServerConfig config) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl();
    }
    
    /**
     * 启动服务器
     */
    public void start() throws Exception {
        // 1. 创建事件循环组
        bossGroup = new NioEventLoopGroup(config.getBossThreads());
        workerGroup = new NioEventLoopGroup(config.getWorkerThreads());
        
        try {
            // 2. 创建服务器启动引导
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                     .channel(NioServerSocketChannel.class)
                     .handler(new LoggingHandler(LogLevel.INFO))  // 日志处理器
                     .childOption(ChannelOption.TCP_NODELAY, true)  // 禁用 Nagle 算法
                     .childOption(ChannelOption.SO_KEEPALIVE, true)  // 保持长连接
                     .childHandler(new ChannelInitializer<SocketChannel>() {
                         @Override
                         protected void initChannel(SocketChannel ch) {
                             // 3. 配置处理器链
                             ch.pipeline()
                               // 入站处理器（按顺序执行）
                               .addLast("idleStateHandler", 
                                       new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS))
                               .addLast("decoder", new RpcProtocolDecoder())
                               .addLast("handler", new RpcRequestHandler(localRegistry))
                               // 出站处理器
                               .addLast("encoder", new RpcProtocolEncoder());
                         }
                     });
            
            // 4. 绑定端口并启动
            InetSocketAddress address = new InetSocketAddress(config.getPort());
            ChannelFuture future = bootstrap.bind(address).sync();
            
            log.info("========================================");
            log.info("RPC 服务器启动成功");
            log.info("监听端口：{}", config.getPort());
            log.info("Boss 线程数：{}", config.getBossThreads());
            log.info("Worker 线程数：{}", config.getWorkerThreads());
            log.info("========================================");
            
            // 5. 等待服务关闭
            future.channel().closeFuture().sync();
            
        } finally {
            shutdown();
        }
    }
    
    /**
     * 优雅关闭
     */
    public void shutdown() {
        log.info("正在关闭 RPC 服务器...");
        
        if (bossGroup != null) {
            bossGroup.shutdownGracefully()
                     .awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }
        
        if (workerGroup != null) {
            workerGroup.shutdownGracefully()
                      .awaitUninterruptibly(config.getShutdownTimeout(), TimeUnit.SECONDS);
        }
        
        log.info("RPC 服务器已关闭");
    }
    
    /**
     * 获取本地服务注册表
     */
    public LocalRegistry getLocalRegistry() {
        return localRegistry;
    }
}
```

---

## 六、使用示例

### 5.1 定义服务接口

```java
package com.rpc.example.api;

/**
 * 测试服务接口
 */
public interface HelloService {
    
    /**
     * 说你好
     * @param name 名字
     * @return 问候语
     */
    String sayHello(String name);
    
    /**
     * 打招呼
     */
    String sayHi(String name);
    
    /**
     * 加法计算
     */
    Integer add(Integer a, Integer b);
}
```

### 5.2 实现服务

```java
package com.rpc.example.provider;

import com.rpc.example.api.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * HelloService 实现类
 */
@Slf4j
public class HelloServiceImpl implements HelloService {
    
    @Override
    public String sayHello(String name) {
        log.info("收到 sayHello 请求：{}", name);
        return "Hello, " + name + "!";
    }
    
    @Override
    public String sayHi(String name) {
        log.info("收到 sayHi 请求：{}", name);
        return "Hi, " + name + "! Nice to meet you!";
    }
    
    @Override
    public Integer add(Integer a, Integer b) {
        log.info("收到 add 请求：{} + {}", a, b);
        return a + b;
    }
}
```

### 5.3 启动 RPC 服务器

```java
package com.rpc.example.provider;

import com.rpc.core.registry.LocalRegistry;
import com.rpc.server.config.RpcServerConfig;
import com.rpc.server.RpcNettyServer;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 服务提供者启动类
 */
@Slf4j
public class RpcProviderBootstrap {
    
    public static void main(String[] args) {
        try {
            // 1. 创建服务器配置
            RpcServerConfig config = RpcServerConfig.custom()
                .port(8080)
                .bossThreads(1)
                .workerThreads(4);
            
            // 2. 创建 RPC 服务器
            RpcNettyServer server = new RpcNettyServer(config);
            
            // 3. 注册服务
            LocalRegistry registry = server.getLocalRegistry();
            registry.register("com.rpc.example.api.HelloService", 
                            HelloServiceImpl.class);
            
            // 4. 启动服务器
            server.start();
            
        } catch (Exception e) {
            log.error("启动 RPC 服务器失败", e);
            System.exit(1);
        }
    }
}
```

### 5.4 运行测试

**步骤 1**：运行 `RpcProviderBootstrap.main()`

输出：
```
========================================
RPC 服务器启动成功
监听端口：8080
Boss 线程数：1
Worker 线程数：4
========================================
信息：服务注册成功：com.rpc.example.api.HelloService -> com.rpc.example.provider.HelloServiceImpl
```

**步骤 2**：使用 Telnet 或 netcat 测试（可选）

```bash
# 安装 netcat
# Windows: 下载 nc.exe
# Linux: sudo apt-get install netcat

# 连接服务器
nc localhost 8080

# 发送测试数据（需要按照协议格式）
```

**步骤 3**：查看日志

服务器会持续运行，等待客户端连接。

---

## 七、优化与最佳实践

### 6.1 服务实例管理

上面的代码每次请求都创建新的服务实例，这不是最佳实践。应该使用单例模式或由 Spring 管理。

**优化方案**：

```java
// 修改 LocalRegistry，直接存储服务实例
public interface LocalRegistry {
    void register(String serviceName, Object serviceInstance);
    Object getService(String serviceName);
}

// 使用时
registry.register("com.rpc.HelloService", new HelloServiceImpl());
```

### 6.2 异常处理增强

```java
try {
    // ... 业务逻辑
} catch (NoSuchMethodException e) {
    rpcResponse = RpcResponse.fail(404, "方法不存在", requestId);
} catch (IllegalAccessException e) {
    rpcResponse = RpcResponse.fail(403, "方法访问被拒绝", requestId);
} catch (InvocationTargetException e) {
    rpcResponse = RpcResponse.fail(500, "方法调用失败：" + e.getCause().getMessage(), requestId);
} catch (Exception e) {
    rpcResponse = RpcResponse.fail(500, "服务器内部错误", requestId);
}
```

### 6.3 添加 IP 白名单

```java
// 在 handler 中添加 IP 校验
@Override
public void channelActive(ChannelHandlerContext ctx) {
    String clientIp = ctx.channel().remoteAddress().getAddress().getHostAddress();
    if (!ipWhitelist.contains(clientIp)) {
        log.warn("非法 IP 访问：{}", clientIp);
        ctx.close();
    }
}
```

---

## 八、本课总结

### 核心知识点

1. **RPC 服务端架构**
   - 网络层（Netty）
   - 协议层（编解码器）
   - 业务层（请求处理器）
   - 注册表（服务映射）

2. **本地服务注册表**
   - 存储服务名称到实现类的映射
   - 使用 ConcurrentHashMap 保证线程安全

3. **请求处理器**
   - 解析 RPC 请求
   - 反射调用服务方法
   - 返回响应结果

4. **Netty 服务端启动**
   - 配置 ServerBootstrap
   - 设置 Boss/Worker 线程组
   - 配置 ChannelPipeline
   - 优雅关闭

### 课后思考

1. 为什么每次请求都创建服务实例不是最佳实践？如何改进？
2. Boss Group 和 Worker Group 的线程数如何设置合理？
3. 如何处理并发请求时的线程安全问题？
4. 如果要支持异步处理，应该如何修改？

---

## 九、动手练习

### 练习 1：实现服务单例

修改 LocalRegistry，使其存储服务实例而不是 Class 对象。

### 练习 2：添加服务统计

为每个服务添加调用次数统计：
- 总调用次数
- 成功次数
- 失败次数
- 平均响应时间

### 练习 3：实现简单的监控

添加一个管理端口（如 8081），可以通过 HTTP 查询服务状态：
```
GET http://localhost:8081/services
返回：{"com.rpc.HelloService": {"count": 100, "success": 98, "fail": 2}}
```

---

## 十、下一步

下一节课我们将实现**Netty 客户端**，发起 RPC 远程调用。

**[跳转到第 7 课：Netty 客户端实现](./lesson-07-netty-client.md)**
