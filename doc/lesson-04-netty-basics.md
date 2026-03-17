# 第 4 课：网络通信基础

## 学习目标

- 理解 BIO、NIO、AIO 的区别
- 掌握 Netty 核心组件和架构
- 学会使用 Netty 编写第一个网络程序
- 理解 Reactor 线程模型

---

## 一、IO 模型详解

### 1.1 什么是 IO？

**IO（Input/Output）** 是指计算机与外部世界（磁盘、网络等）进行数据交换的过程。

在 Java 中，主要有三种 IO 模型：
- **BIO**：Blocking IO（阻塞 IO）
- **NIO**：Non-blocking IO（非阻塞 IO）
- **AIO**：Asynchronous IO（异步 IO）

### 1.2 BIO（阻塞 IO）

#### 工作原理

```java
ServerSocket server = new ServerSocket(8080);

// ① 阻塞等待客户端连接
Socket socket = server.accept();  // ← 阻塞在这里，直到有客户端连接

// ② 阻塞读取数据
InputStream is = socket.getInputStream();
byte[] buffer = new byte[1024];
int len = is.read(buffer);  // ← 阻塞在这里，直到有数据可读

// ③ 处理业务逻辑
process(buffer);

// ④ 返回响应
OutputStream os = socket.getOutputStream();
os.write(response);
```

#### BIO 的特点

```
┌──────────────────────────────────────┐
│   一个连接 = 一个线程                 │
├──────────────────────────────────────┤
│ ✅ 优点：                            │
│   - 代码简单，易于理解               │
│   - 适合连接数少的场景               │
├──────────────────────────────────────┤
│ ❌ 缺点：                            │
│   - 资源消耗大（每个连接都要线程）   │
│   - 并发能力差（线程数有限制）       │
│   - 性能低（大量线程切换开销）       │
└──────────────────────────────────────┘
```

#### 适用场景

- ✅ 连接数固定且较少（如数据库连接池）
- ✅ 架构简单的小型应用
- ❌ **不适合高并发的 RPC 框架**

---

### 1.3 NIO（非阻塞 IO）

#### 工作原理

NIO 引入了三个核心概念：**Channel**、**Buffer**、**Selector**。

```java
// ① 创建 Channel（通道）
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8080));

// ② 创建 Selector（选择器）
Selector selector = Selector.open();

// ③ 注册 Channel 到 Selector（非阻塞模式）
server.configureBlocking(false);
server.register(selector, SelectionKey.OP_ACCEPT);

// ④ 轮询就绪的事件
while (true) {
    // 查询是否有事件发生（连接、读、写）
    int readyChannels = selector.select();  // 阻塞到有事件
    
    if (readyChannels == 0) continue;
    
    // 获取所有就绪的 SelectionKey
    Set<SelectionKey> keys = selector.selectedKeys();
    
    for (SelectionKey key : keys) {
        if (key.isAcceptable()) {
            // 有新连接
            SocketChannel channel = server.accept();
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ);
        } else if (key.isReadable()) {
            // 有数据可读
            SocketChannel channel = (SocketChannel) key.channel();
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            channel.read(buffer);
            // 处理数据...
        }
    }
}
```

#### NIO 的核心组件

**（1）Channel（通道）**
- 双向的，既可以读也可以写
- 常见的 Channel：`SocketChannel`、`ServerSocketChannel`

**（2）Buffer（缓冲区）**
- 所有数据都通过 Buffer 读写
- 常用的 Buffer：`ByteBuffer`

**（3）Selector（选择器）**
- 监听多个 Channel 的事件
- 单线程可以管理成千上万个连接

#### NIO vs BIO 对比

| 特性 | BIO | NIO |
|------|-----|-----|
| **阻塞方式** | 阻塞 | 非阻塞 |
| **连接处理** | 一个连接一个线程 | 一个线程多个连接 |
| **并发能力** | 低（受线程数限制） | 高（单线程可管理万级连接） |
| **编程复杂度** | 简单 | 复杂 |
| **适用场景** | 连接数少 | 高并发 |

#### NIO 的优势

```
假设我们有 10000 个并发连接：

BIO 方案：
- 需要 10000 个线程
- 内存占用：10000 × 1MB = 10GB（假设每个线程栈 1MB）
- CPU 消耗：大量线程切换
- 结果：系统崩溃 😰

NIO 方案：
- 只需要 1-2 个线程
- 内存占用：2 × 1MB = 2MB
- CPU 消耗：几乎没有线程切换
- 结果：轻松应对 😎
```

---

### 1.4 AIO（异步 IO）

#### 工作原理

AIO 是基于事件驱动的异步 IO 模型。

```java
// ① 发起异步读请求（立即返回，不阻塞）
channel.read(buffer, attachment, new CompletionHandler<Integer, Object>() {
    
    @Override
    public void completed(Integer result, Object att) {
        // ③ 读操作完成后，回调这个方法
        System.out.println("读取完成：" + result);
    }
    
    @Override
    public void failed(Throwable exc, Object att) {
        // 失败回调
        exc.printStackTrace();
    }
});

// ② 继续做其他事情（不阻塞等待）
doOtherThings();
```

#### AIO 的特点

- ✅ **真正的异步**：IO 操作完全不阻塞线程
- ✅ **基于事件驱动**：操作完成后回调通知
- ✅ **性能最优**：理论上比 NIO 更好
- ❌ **实现复杂**：编程模型复杂
- ❌ **生态不成熟**：Netty 对 AIO 支持有限

#### 为什么不用 AIO？

虽然 AIO 理论上性能最好，但：
1. Windows 上实现较好，Linux 上实现不成熟
2. Netty 主要优化 NIO，AIO 使用较少
3. 编程复杂度高，学习成本大
4. 实际测试中，NIO 性能已经足够优秀

**结论**：NIO 是当前的最佳选择 ⭐

---

### 1.5 三种 IO 模型对比

#### 生活化比喻

**BIO（去餐厅吃饭）**：
```
你点菜后 → 站在厨房门口等着 → 厨师做好给你 → 拿到菜回座位
（全程阻塞，什么也干不了）
```

**NIO（去餐厅吃饭）**：
```
你点菜后 → 拿个号码牌 → 回座位玩手机 → 听到叫号去取菜
（可以做其他事情，定期查看是否完成）
```

**AIO（去餐厅吃饭）**：
```
你点菜后 → 回座位玩手机 → 服务员直接把菜端到你桌上
（完全被动通知，不需要主动查询）
```

#### 技术对比表

| 特性 | BIO | NIO | AIO |
|------|-----|-----|-----|
| **阻塞方式** | 阻塞 | 非阻塞 | 异步 |
| **线程模型** | 一个连接一个线程 | 一个线程多个连接 | 一个请求一个回调 |
| **并发能力** | 低 | 高 | 很高 |
| **编程难度** | 简单 | 中等 | 复杂 |
| **适用场景** | 小规模应用 | 高并发应用 | 超高并发 |
| **Netty 支持** | 支持 | **主要支持** ⭐ | 有限支持 |

---

## 二、Netty 入门

### 2.1 什么是 Netty？

**Netty** 是一个基于 NIO 的、高性能的、异步的网络应用程序框架。

**简单来说**：Netty 封装了复杂的 NIO 编程细节，让我们用简单的 API 就能写出高性能的网络程序。

### 2.2 为什么选择 Netty？

#### 原生 NIO 编程的问题

```java
// 原生 NIO 代码示例
Selector selector = Selector.open();
serverSocketChannel.configureBlocking(false);
serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

while (true) {
    int readyChannels = selector.select();
    if (readyChannels == 0) continue;
    
    Set<SelectionKey> keys = selector.selectedKeys();
    Iterator<SelectionKey> iterator = keys.iterator();
    
    while (iterator.hasNext()) {
        SelectionKey key = iterator.next();
        iterator.remove();  // ⚠️ 必须手动移除，否则会出现问题
        
        try {
            if (key.isAcceptable()) {
                SocketChannel channel = serverSocketChannel.accept();
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ);
            } else if (key.isReadable()) {
                SocketChannel channel = (SocketChannel) key.channel();
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                int bytesRead = channel.read(buffer);
                
                if (bytesRead == -1) {
                    key.cancel();
                    channel.close();
                } else {
                    // 处理粘包、拆包等问题...
                }
            }
        } catch (IOException e) {
            key.cancel();
            key.channel().close();
        }
    }
}
```

**问题**：
- ❌ 代码复杂，容易出错
- ❌ 需要处理各种边界情况（粘包、拆包、半包）
- ❌ 需要自己管理线程池
- ❌ 需要处理断线重连、心跳检测等

#### Netty 的优势

```java
// Netty 代码示例
public class NettyServer {
    public static void main(String[] args) {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     protected void initChannel(SocketChannel ch) {
                         ch.pipeline().addLast(new MyHandler());
                     }
                 });
        
        ChannelFuture future = bootstrap.bind(8080).sync();
        future.channel().closeFuture().sync();
    }
}
```

**优势**：
- ✅ **API 简洁**：代码量减少 80%
- ✅ **功能丰富**：内置多种编解码器
- ✅ **性能卓越**：经过极致优化
- ✅ **稳定可靠**：经受过生产环境考验
- ✅ **生态完善**：文档丰富，社区活跃

### 2.3 Netty 的应用案例

众多知名公司和框架都在使用 Netty：

- **阿里巴巴**：Dubbo RPC 框架
- **腾讯**：微信后台
- **百度**：搜索架构
- **Spark**：分布式计算框架
- **Elasticsearch**：搜索引擎
- **Flink**：流式计算框架

---

## 三、Netty 核心组件

### 3.1 Netty 架构图

```
┌─────────────────────────────────────────────────────────┐
│                    Netty 架构                            │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────┐         ┌──────────────┐             │
│  │  Boss Group  │         │ Worker Group │             │
│  │ (接收连接)   │  ────→  │ (处理 IO)     │             │
│  └──────────────┘         └──────────────┘             │
│         ↓                       ↓                        │
│  ┌──────────────┐         ┌──────────────┐             │
│  │  Acceptor    │         │    Handler   │             │
│  │  Thread      │         │    Thread    │             │
│  └──────────────┘         └──────────────┘             │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │              Channel Pipeline                    │  │
│  │  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐ │  │
│  │  │Decoder │→ │Handler1│→ │Handler2│→ │Encoder │ │  │
│  │  └────────┘  └────────┘  └────────┘  └────────┘ │  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 核心组件详解

#### （1）Bootstrap / ServerBootstrap

**启动引导类**，用于配置和启动 Netty 应用。

```java
// 服务端
ServerBootstrap bootstrap = new ServerBootstrap();

// 客户端
Bootstrap bootstrap = new Bootstrap();
```

#### （2）EventLoopGroup

**事件循环组**，本质是一个线程池。

```java
// Boss Group：负责接收客户端连接
EventLoopGroup bossGroup = new NioEventLoopGroup(1);

// Worker Group：负责处理 IO 事件（读写）
EventLoopGroup workerGroup = new NioEventLoopGroup();  // 默认 CPU 核数 * 2
```

**两个 Group 的职责**：
```
Boss Group                      Worker Group
     ↓                               ↓
专门接收连接                    处理连接的读写
就像餐厅迎宾员                  就像餐厅服务员
```

#### （3）Channel

**网络通道**，封装了一个网络连接。

```java
// 常见的 Channel 类型
NioServerSocketChannel  // 服务端监听端口
NioSocketChannel        // 客户端连接
```

**Channel 的方法**：
```java
channel.bind(8080);           // 绑定端口
channel.connect(...);         // 连接服务器
channel.writeAndFlush(msg);   // 发送数据
channel.close();              // 关闭连接
```

#### （4）ChannelHandler

**处理器**，负责处理 IO 事件。

```java
public class MyHandler extends SimpleChannelInboundHandler<String> {
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        // 收到消息时的处理逻辑
        System.out.println("收到：" + msg);
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 客户端连接时触发
        System.out.println("客户端连接：" + ctx.channel().remoteAddress());
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 客户端断开时触发
        System.out.println("客户端断开");
    }
}
```

#### （5）ChannelPipeline

**处理器管道**，ChannelHandler 的容器。

```
数据流入（入站）                数据流出（出站）
     ↓                              ↑
┌─────────────────────────────────────────┐
│            Channel Pipeline             │
│                                         │
│  ┌─────────────┐                        │
│  │ Decoder    │ (入站处理器 1)           │
│  ├─────────────┤                        │
│  │ Handler1   │ (入站处理器 2)           │
│  ├─────────────┤                        │
│  │ Encoder    │ (出站处理器 1)     →    │
│  └─────────────┘                        │
└─────────────────────────────────────────┘
```

**添加处理器**：
```java
pipeline.addLast("decoder", new MyDecoder());
pipeline.addLast("handler", new MyHandler());
pipeline.addLast("encoder", new MyEncoder());
```

#### （6）ChannelFuture

**异步结果**，用于获取异步操作的结果。

```java
// bind 是异步的，立即返回
ChannelFuture future = bootstrap.bind(8080);

// 等待绑定完成
future.sync();  // 阻塞等待

// 添加监听器（推荐方式）
future.addListener((ChannelFutureListener) f -> {
    if (f.isSuccess()) {
        System.out.println("绑定成功");
    } else {
        System.out.println("绑定失败");
    }
});
```

---

## 四、第一个 Netty 程序

### 4.1 Maven 依赖

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
    <version>4.1.94.Final</version>
</dependency>
```

### 4.2 服务端代码

```java
package com.rpc.server.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class NettyServer {
    
    public static void main(String[] args) throws Exception {
        // 1. 创建 Boss Group 和 Worker Group
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        try {
            // 2. 创建服务器启动引导
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                     .channel(NioServerSocketChannel.class)
                     .childHandler(new ChannelInitializer<SocketChannel>() {
                         @Override
                         protected void initChannel(SocketChannel ch) {
                             // 3. 配置处理器链
                             ChannelPipeline pipeline = ch.pipeline();
                             pipeline.addLast(new StringDecoder());  // 解码器
                             pipeline.addLast(new StringEncoder());  // 编码器
                             pipeline.addLast(new ServerHandler());  // 业务处理器
                         }
                     });
            
            // 4. 绑定端口并启动
            ChannelFuture future = bootstrap.bind(8080).sync();
            
            System.out.println("Netty 服务器启动成功，监听端口：8080");
            
            // 5. 等待服务关闭
            future.channel().closeFuture().sync();
            
        } finally {
            // 6. 优雅关闭
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
    
    /**
     * 服务端处理器
     */
    static class ServerHandler extends SimpleChannelInboundHandler<String> {
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            System.out.println("客户端连接：" + ctx.channel().remoteAddress());
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            System.out.println("收到客户端消息：" + msg);
            
            // 回复消息
            ctx.writeAndFlush("服务端已收到：" + msg);
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            System.out.println("客户端断开连接：" + ctx.channel().remoteAddress());
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
```

### 4.3 客户端代码

```java
package com.rpc.client.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class NettyClient {
    
    public static void main(String[] args) throws Exception {
        // 1. 创建事件循环组（客户端只需要一个）
        EventLoopGroup group = new NioEventLoopGroup();
        
        try {
            // 2. 创建客户端启动引导
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                     .channel(NioSocketChannel.class)
                     .handler(new ChannelInitializer<SocketChannel>() {
                         @Override
                         protected void initChannel(SocketChannel ch) {
                             ChannelPipeline pipeline = ch.pipeline();
                             pipeline.addLast(new StringDecoder());
                             pipeline.addLast(new StringEncoder());
                             pipeline.addLast(new ClientHandler());
                         }
                     });
            
            // 3. 连接服务器
            ChannelFuture future = bootstrap.connect("127.0.0.1", 8080).sync();
            
            System.out.println("Netty 客户端启动成功");
            
            // 4. 发送消息
            Channel channel = future.channel();
            channel.writeAndFlush("你好，服务端！");
            
            // 等待 1 秒接收响应
            Thread.sleep(1000);
            
            // 5. 关闭连接
            channel.closeFuture().sync();
            
        } finally {
            group.shutdownGracefully();
        }
    }
    
    /**
     * 客户端处理器
     */
    static class ClientHandler extends SimpleChannelInboundHandler<String> {
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            System.out.println("收到服务端响应：" + msg);
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            System.out.println("已连接到服务端");
        }
    }
}
```

### 4.4 运行测试

**步骤 1**：先启动服务端
```
运行 NettyServer.main()
输出：Netty 服务器启动成功，监听端口：8080
```

**步骤 2**：再启动客户端
```
运行 NettyClient.main()
输出：
Netty 客户端启动成功
已连接到服务端
收到服务端响应：服务端已收到：你好，服务端！
```

**步骤 3**：查看服务端输出
```
客户端连接：/127.0.0.1:xxxx
收到客户端消息：你好，服务端！
客户端断开连接：/127.0.0.1:xxxx
```

---

## 五、Reactor 线程模型

Netty 基于 Reactor 模式设计，有两种主要的 Reactor 模型。

### 5.1 单 Reactor 单线程

```
┌──────────────────────────────┐
│         Selector             │
│  accept/read/write 都在一个线程 │
└──────────────────────────────┘
           ↓
    ┌─────────────┐
    │  业务处理    │
    └─────────────┘
```

**特点**：
- 所有 IO 操作和业务处理都在同一个线程
- 简单，但无法利用多核 CPU
- 只适用于低并发场景

### 5.2 单 Reactor 多线程 ⭐ Netty 采用的模型

```
┌──────────────────┐
│   Boss Reactor   │ (accept 连接)
└────────┬─────────┘
         ↓
┌──────────────────┐
│  Worker Reactor  │ (read/write)
│  (线程池，多个)   │
└────────┬─────────┘
         ↓
    ┌─────────┐
    │业务处理  │ (可选独立的线程池)
    └─────────┘
```

**Netty 的实现**：
```java
// Boss Group（单线程或少量线程）
EventLoopGroup bossGroup = new NioEventLoopGroup(1);

// Worker Group（多个线程）
EventLoopGroup workerGroup = new NioEventLoopGroup();  // CPU 核数 * 2
```

**特点**：
- ✅ Boss 线程只负责接收连接
- ✅ Worker 线程池负责处理 IO
- ✅ 可以充分利用多核 CPU
- ✅ 适用于高并发场景

### 5.3 主从 Reactor 多线程

```
┌──────────────────┐
│  Boss Reactor    │ (多线程)
│  (接收连接)      │
└────────┬─────────┘
         ↓ 分发给
┌──────────────────┐
│ Worker Reactor   │ (多线程)
│ (处理 IO)        │
└──────────────────┘
```

**特点**：
- Boss 和 Worker 都是线程池
- 适用于超高并发场景
- Netty 默认配置就是这种模型

---

## 六、本课总结

### 核心知识点

1. **IO 模型对比**
   - **BIO**：阻塞，一个连接一个线程，适合小规模
   - **NIO**：非阻塞，一个线程多个连接，适合高并发 ⭐
   - **AIO**：异步，基于事件驱动，生态不成熟

2. **为什么选择 Netty**
   - 封装了复杂的 NIO 细节
   - 提供简洁易用的 API
   - 性能卓越，稳定可靠
   - 生态完善，广泛使用

3. **Netty 核心组件**
   - **Bootstrap/ServerBootstrap**：启动引导
   - **EventLoopGroup**：事件循环组（线程池）
   - **Channel**：网络通道
   - **ChannelHandler**：处理器
   - **ChannelPipeline**：处理器管道
   - **ChannelFuture**：异步结果

4. **Reactor 线程模型**
   - 单 Reactor 单线程：简单但性能差
   - 单 Reactor 多线程：Netty 采用 ⭐
   - 主从 Reactor 多线程：超高并发

### 课后思考

1. 为什么 NIO 比 BIO 更适合高并发场景？
2. Netty 为什么要设计 Boss Group 和 Worker Group 两个线程池？
3. ChannelPipeline 中处理器的顺序有什么讲究？
4. 如果 Worker Group 设置为 1 个线程，会有什么影响？

---

## 七、动手练习

### 练习 1：实现简单的聊天室

修改上面的代码，实现一个多人聊天室：
- 服务端可以接收多个客户端连接
- 一个客户端发送的消息，广播给所有其他客户端
- 显示用户上线/下线通知

提示：使用 `ChannelGroup` 管理所有连接。

### 练习 2：实现回声服务器

创建一个回声服务器（Echo Server）：
- 客户端发送什么，服务端就回复什么
- 统计每个客户端发送的消息数量
- 客户端断开时，打印统计信息

### 练习 3：压力测试

使用 JMeter 或自己编写代码，测试 Netty 服务端：
- 同时开启 1000 个客户端连接
- 观察服务端的 CPU 和内存使用情况
- 与 BIO 实现进行对比

---

## 八、下一步

下一节课我们将**设计 RPC 通信协议**，这是 RPC 框架的基石。

**[跳转到第 5 课：设计 RPC 通信协议](./lesson-05-protocol-design.md)**

---

## 附录：Netty 常用注解

| 注解 | 说明 |
|------|------|
| `@Sharable` | 标记 Handler 可以被多个 Channel 共享 |
| `@ChannelHandler.Sharable` | 同上 |

**使用示例**：
```java
@Sharable
public class SharedHandler extends SimpleChannelInboundHandler<String> {
    // 这个 Handler 实例可以被多个 Channel 共用
}
```

如果没有加 `@Sharable` 注解，每个 Channel 都需要创建新的 Handler 实例。
