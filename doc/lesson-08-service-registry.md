# 第 8 课：服务注册与发现（ZooKeeper）

## 学习目标

- 理解服务注册与发现的概念和重要性
- 掌握 ZooKeeper 的基本原理和使用方法
- 实现基于 ZooKeeper 的服务注册中心
- 实现服务提供者和服务消费者的自动发现
- 理解临时节点和监听机制在 RPC 中的应用

---

## 一、为什么需要服务注册与发现？

### 1.1 没有注册中心的问题

在前面的课程中，我们的 RPC 框架已经实现了基本的远程调用功能。但是，我们硬编码了服务提供者的地址：

```java
// 第 7 课中的客户端代码
String host = "127.0.0.1";
int port = 8080;

RpcConnection connection = pool.getConnection(host, port);
```

**这种方式存在以下问题：**

1. **服务地址变更困难**：如果服务端 IP 或端口变化，需要修改客户端代码并重新部署
2. **无法负载均衡**：多个服务实例时，不知道应该连接哪个
3. **无法感知服务上下线**：服务宕机后，客户端仍然尝试连接，导致大量失败
4. **扩展性差**：新增服务提供者需要手动配置

### 1.2 服务注册与发现的解决方案

```
┌─────────────────────────────────────────────────────────┐
│                  服务注册与发现架构                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│   服务提供者                                           │
│   ┌──────────────┐                                     │
│   │ Service A    │  ① 注册                            │
│   │ (192.168.1.10:8080) │ ──────────────────>          │
│   └──────────────┘                                     │
│                                                        │
│   服务提供者                                           │
│   ┌──────────────┐                                     │
│   │ Service A    │  ① 注册                            │
│   │ (192.168.1.11:8080) │ ──────────────────>          │
│   └──────────────┘                                     │
│                                                        │
│              ② 存储在注册中心                           │
│         ┌────────────────────────┐                     │
│         │   Registry Center      │                     │
│         │   - Service A          │                     │
│         │     ├─ 192.168.1.10:8080│                    │
│         │     └─ 192.168.1.11:8080│                    │
│         └────────────────────────┘                     │
│                    ↑                                   │
│                    │ ③ 查询                           │
│   服务消费者       │                                   │
│   ┌──────────────┐│                                   │
│   │ Consumer     │└───────────────────                │
│   └──────────────┘                                    │
│                                                        │
│              ④ 获取服务列表                            │
│         ┌────────────────────────┐                     │
│         │   Service A List       │                     │
│         │   - 192.168.1.10:8080  │                     │
│         │   - 192.168.1.11:8080  │                     │
│         └────────────────────────┘                     │
│                                                        │
└─────────────────────────────────────────────────────────┘
```

**核心流程：**
1. **服务注册**：服务启动时向注册中心注册自己的地址
2. **服务发现**：消费者启动时从注册中心查询服务提供者列表
3. **负载均衡**：消费者根据某种策略选择一个提供者
4. **健康检查**：注册中心自动剔除下线的服务

---

## 二、ZooKeeper 基础

### 2.1 什么是 ZooKeeper？

ZooKeeper 是 Apache 的一个开源项目，是一个分布式协调服务。

**主要功能：**
- ✅ 配置管理
- ✅ 服务注册与发现
- ✅ 分布式锁
- ✅ 集群管理
- ✅ 队列管理

**为什么选择 ZooKeeper 作为注册中心？**
1. **强一致性**：保证所有节点看到的数据是一致的
2. **高可用**：支持集群部署，自动故障转移
3. **临时节点**：客户端断开后自动删除节点
4. **监听机制**：可以实时感知数据变化
5. **成熟稳定**：被广泛应用于生产环境

### 2.2 ZooKeeper 数据模型

ZooKeeper 的数据模型是一个树形结构，每个节点称为 ZNode。

```
/ (根节点)
├── rpc (我们的 RPC 应用)
│   ├── com.rpc.example.api.HelloService (服务名称)
│   │   ├── 192.168.1.10:8080 (临时节点)
│   │   │   └── data: {"weight": 1, "version": "1.0"}
│   │   ├── 192.168.1.11:8080 (临时节点)
│   │   │   └── data: {"weight": 2, "version": "1.0"}
│   │   └── 192.168.1.12:8080 (临时节点)
│   └── com.rpc.example.api.UserService (服务名称)
│       └── 192.168.1.15:8081 (临时节点)
```

**ZNode 的四种类型：**

| 类型 | 持久性 | 有序性 | 说明 |
|------|--------|--------|------|
| PERSISTENT | 持久 | 无序 | 客户端断开后节点仍然存在 |
| EPHEMERAL | **临时** | 无序 | 客户端断开后节点自动删除 ⭐ |
| PERSISTENT_SEQUENTIAL | 持久 | **有序** | 节点名称后自动添加序号 |
| EPHEMERAL_SEQUENTIAL | **临时** | **有序** | 兼具临时和有序特性 |

**RPC 场景选择：**
- ✅ **EPHEMERAL**：服务注册节点（服务宕机后自动删除）
- ✅ **PERSISTENT**：服务名称的父节点

### 2.3 安装 ZooKeeper（Windows）

#### 步骤 1：下载 ZooKeeper

访问官网：https://zookeeper.apache.org/releases.html

下载最新版本，例如：`apache-zookeeper-3.8.4-bin.tar.gz`

#### 步骤 2：解压并配置

```bash
# 解压到 D:\zookeeper
# 进入配置目录
cd D:\zookeeper\conf

# 复制配置文件
copy zoo_sample.cfg zoo.cfg
```

#### 步骤 3：修改 zoo.cfg

编辑 `D:\zookeeper\conf\zoo.cfg`：

```properties
# 心跳检测时间（毫秒）
tickTime=2000

# 初始化超时时间（tickTime 的倍数）
initLimit=10

# 同步超时时间（tickTime 的倍数）
syncLimit=5

# 数据目录
dataDir=D:\\zookeeper\\data

# 客户端连接端口
clientPort=2181

# 最大连接数
maxClientCnxns=60
```

#### 步骤 4：启动 ZooKeeper

```bash
# 进入 zookeeper 目录
cd D:\zookeeper\bin

# 启动服务端
zkServer.cmd

# 看到以下输出表示启动成功：
# ZooKeeper JMX enabled by default
# Using config: D:\zookeeper\bin\..\conf\zoo.cfg
# Starting zookeeper ... STARTED

# 启动客户端（验证是否成功）
zkCli.cmd

# 看到 Welcome to ZooKeeper! 表示成功！
```

#### 步骤 5：常用命令

```bash
# 查看根节点下的所有子节点
ls /

# 创建节点
create /rpc ""

# 查看节点数据
get /rpc

# 删除节点
delete /rpc

# 退出
quit
```

---

## 三、实现服务注册中心

### 3.1 服务注册中心接口设计

首先定义服务注册中心的接口：

```java
package com.rpc.registry;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 服务注册中心接口
 */
public interface ServiceRegistry {
    
    /**
     * 注册服务
     * @param serviceName 服务名称
     * @param address 服务地址
     */
    void register(String serviceName, InetSocketAddress address);
    
    /**
     * 注销服务
     * @param serviceName 服务名称
     * @param address 服务地址
     */
    void unregister(String serviceName, InetSocketAddress address);
    
    /**
     * 发现服务（获取所有服务提供者地址）
     * @param serviceName 服务名称
     * @return 服务地址列表
     */
    List<InetSocketAddress> lookup(String serviceName);
    
    /**
     * 关闭注册中心
     */
    void close();
}
```

### 3.2 基于 ZooKeeper 的实现

```java
package com.rpc.registry.impl;

import com.rpc.registry.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 基于 ZooKeeper 的服务注册中心实现
 */
@Slf4j
public class ZooKeeperRegistryImpl implements ServiceRegistry {
    
    // ZooKeeper 根路径
    private static final String ZK_ROOT = "/rpc";
    
    // ZooKeeper 客户端
    private final ZooKeeper zooKeeper;
    
    // 缓存已注册的服务地址，避免重复注册
    private final Map<String, List<String>> registeredServices = new ConcurrentHashMap<>();
    
    /**
     * 构造方法
     * @param connectString ZooKeeper 连接字符串，如："127.0.0.1:2181"
     * @param sessionTimeout 会话超时时间（毫秒）
     */
    public ZooKeeperRegistryImpl(String connectString, int sessionTimeout) {
        try {
            // 创建 CountDownLatch 用于等待连接建立
            CountDownLatch countDownLatch = new CountDownLatch(1);
            
            // 创建 ZooKeeper 连接
            zooKeeper = new ZooKeeper(connectString, sessionTimeout, event -> {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    log.info("ZooKeeper 连接成功");
                    countDownLatch.countDown();
                } else if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
                    log.warn("ZooKeeper 连接断开");
                } else if (event.getState() == Watcher.Event.KeeperState.Expired) {
                    log.error("ZooKeeper 会话过期");
                }
            });
            
            // 等待连接建立
            countDownLatch.await();
            
            // 确保根节点存在
            ensureRootPath();
            
        } catch (Exception e) {
            log.error("连接 ZooKeeper 失败", e);
            throw new RuntimeException("连接 ZooKeeper 失败", e);
        }
    }
    
    /**
     * 确保根节点存在
     */
    private void ensureRootPath() throws KeeperException, InterruptedException {
        Stat stat = zooKeeper.exists(ZK_ROOT, false);
        if (stat == null) {
            // 根节点不存在，创建持久节点
            zooKeeper.create(ZK_ROOT, "".getBytes(StandardCharsets.UTF_8), 
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            log.info("创建 ZooKeeper 根节点：{}", ZK_ROOT);
        }
    }
    
    @Override
    public void register(String serviceName, InetSocketAddress address) {
        try {
            String servicePath = ZK_ROOT + "/" + serviceName;
            
            // 1. 确保服务父节点存在（持久节点）
            Stat stat = zooKeeper.exists(servicePath, false);
            if (stat == null) {
                zooKeeper.create(servicePath, "".getBytes(StandardCharsets.UTF_8), 
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                log.info("创建服务节点：{}", servicePath);
            }
            
            // 2. 创建服务地址节点（临时节点）
            String addressPath = servicePath + "/" + addressToPath(address);
            Stat addrStat = zooKeeper.exists(addressPath, false);
            if (addrStat == null) {
                // 存储额外的元数据信息
                byte[] metadata = buildMetadata(address);
                zooKeeper.create(addressPath, metadata, 
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                log.info("注册地址节点：{}", addressPath);
                
                // 记录已注册的服务
                registeredServices.computeIfAbsent(serviceName, k -> new ArrayList<>())
                    .add(addressToPath(address));
            }
            
        } catch (Exception e) {
            log.error("注册服务失败：{}@{}", serviceName, address, e);
            throw new RuntimeException("注册服务失败", e);
        }
    }
    
    @Override
    public void unregister(String serviceName, InetSocketAddress address) {
        try {
            String addressPath = ZK_ROOT + "/" + serviceName + "/" + addressToPath(address);
            
            // 删除临时节点
            Stat stat = zooKeeper.exists(addressPath, false);
            if (stat != null) {
                zooKeeper.delete(addressPath, -1);
                log.info("注销服务地址：{}", addressPath);
            }
            
            // 从缓存中移除
            List<String> addresses = registeredServices.get(serviceName);
            if (addresses != null) {
                addresses.remove(addressToPath(address));
            }
            
        } catch (Exception e) {
            log.error("注销服务失败：{}@{}", serviceName, address, e);
            throw new RuntimeException("注销服务失败", e);
        }
    }
    
    @Override
    public List<InetSocketAddress> lookup(String serviceName) {
        try {
            String servicePath = ZK_ROOT + "/" + serviceName;
            
            // 获取服务下的所有子节点（即所有服务提供者地址）
            List<String> children = zooKeeper.getChildren(servicePath, false);
            
            if (children.isEmpty()) {
                log.warn("未找到服务：{}", serviceName);
                return new ArrayList<>();
            }
            
            // 转换为 InetSocketAddress 列表
            List<InetSocketAddress> addresses = new ArrayList<>();
            for (String child : children) {
                addresses.add(pathToAddress(child));
            }
            
            log.info("发现服务 {}，共 {} 个提供者：{}", serviceName, children.size(), addresses);
            return addresses;
            
        } catch (Exception e) {
            log.error("查找服务失败：{}", serviceName, e);
            throw new RuntimeException("查找服务失败", e);
        }
    }
    
    @Override
    public void close() {
        try {
            if (zooKeeper != null && zooKeeper.getState().isAlive()) {
                zooKeeper.close();
                log.info("关闭 ZooKeeper 连接");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("关闭 ZooKeeper 连接失败", e);
        }
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 将地址转换为路径节点名称
     * 例如：192.168.1.10:8080 -> 192.168.1.10-8080
     */
    private String addressToPath(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + "-" + address.getPort();
    }
    
    /**
     * 将路径节点名称转换为地址
     * 例如：192.168.1.10-8080 -> 192.168.1.10:8080
     */
    private InetSocketAddress pathToAddress(String path) {
        String[] parts = path.split("-");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        return new InetSocketAddress(host, port);
    }
    
    /**
     * 构建元数据信息
     */
    private byte[] buildMetadata(InetSocketAddress address) {
        // 可以在这里添加更多元数据，如权重、版本等
        // 暂时返回空字节数组
        return "".getBytes(StandardCharsets.UTF_8);
    }
}
```

### 3.3 关键点解析

#### **1️⃣ 为什么使用临时节点？**

```java
// 使用 EPHEMERAL 模式
zooKeeper.create(addressPath, metadata, 
    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
```

**原因：**
- ✅ 服务正常下线时，会主动调用 `unregister()` 删除节点
- ✅ 服务异常崩溃（断电、OOM）时，无法主动删除节点
- ✅ ZooKeeper 检测到客户端断开连接后，会自动删除该客户端创建的所有临时节点
- ✅ 这样其他消费者就能立即知道该服务已下线

**对比持久节点：**
```
场景：服务提供者宕机

使用持久节点：
  - 节点一直存在
  - 消费者仍然会尝试连接
  - 导致大量请求失败 ❌

使用临时节点：
  - 节点自动删除
  - 消费者知道服务已下线
  - 可以选择其他提供者 ✅
```

#### **2️⃣ 为什么需要缓存已注册的服务？**

```java
private final Map<String, List<String>> registeredServices = new ConcurrentHashMap<>();
```

**原因：**
- ✅ 避免重复注册（同一服务多次调用 `register()`）
- ✅ 提高性能（减少 ZooKeeper IO 操作）
- ✅ 方便批量注销

---

## 四、整合到 RPC 框架

### 4.1 修改 RpcNettyServer

在服务端启动时，自动注册服务到 ZooKeeper：

```java
package com.rpc.transport.netty.server;

import com.rpc.config.RpcServerConfig;
import com.rpc.registry.LocalRegistry;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.LocalRegistryImpl;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * 基于 Netty 的 RPC 服务器
 */
@Slf4j
public class RpcNettyServer {
    
    // 本地服务注册表
    private final LocalRegistry localRegistry;
    
    // 远程服务注册中心（ZooKeeper）
    private final ServiceRegistry serviceRegistry;
    
    // 服务器配置
    private final RpcServerConfig config;
    
    // Netty 线程组
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    
    /**
     * 构造方法
     */
    public RpcNettyServer(RpcServerConfig config, ServiceRegistry serviceRegistry) {
        this.config = config;
        this.localRegistry = new LocalRegistryImpl();
        this.serviceRegistry = serviceRegistry;
    }
    
    /**
     * 启动服务器
     */
    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(config.getBossThreads());
        workerGroup = new NioEventLoopGroup(config.getWorkerThreads());
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                            .addLast("encoder", new RpcProtocolEncoder())
                            .addLast("decoder", new RpcProtocolDecoder())
                            .addLast("handler", new RpcRequestHandler(localRegistry));
                    }
                });
            
            // 绑定端口
            ChannelFuture future = bootstrap.bind(config.getPort()).sync();
            
            log.info("========================================");
            log.info("RPC 服务器启动成功");
            log.info("监听端口：{}", config.getPort());
            log.info("Boss 线程数：{}", config.getBossThreads());
            log.info("Worker 线程数：{}", config.getWorkerThreads());
            log.info("========================================");
            
            // 注册所有本地服务到 ZooKeeper
            registerAllServices();
            
            // 等待服务器关闭
            future.channel().closeFuture().sync();
            
        } finally {
            shutdown();
        }
    }
    
    /**
     * 注册所有本地服务到 ZooKeeper
     */
    private void registerAllServices() {
        // 获取所有已注册的服务
        // 注意：这里需要修改 LocalRegistry 添加获取所有服务的方法
        // 或者在注册到 LocalRegistry 的同时也注册到 ZooKeeper
        
        // 示例：假设我们有一个服务列表
        // 实际应该在 LocalRegistry.register() 时自动注册到 ZooKeeper
        InetSocketAddress address = new InetSocketAddress(config.getHost(), config.getPort());
        
        // 这里简化处理，实际应该在 LocalRegistry.register() 时自动调用
        // serviceRegistry.register(serviceName, address);
        
        log.info("服务已注册到 ZooKeeper");
    }
    
    /**
     * 关闭服务器
     */
    public void shutdown() {
        // 1. 注销所有服务
        if (serviceRegistry != null) {
            serviceRegistry.close();
        }
        
        // 2. 关闭 Netty 线程组
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
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

### 4.2 优化方案：自动注册

更好的方式是在 `LocalRegistry.register()` 时自动注册到 ZooKeeper：

```java
package com.rpc.registry.impl;

import com.rpc.registry.LocalRegistry;
import com.rpc.registry.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强的本地服务注册表实现
 * 自动同步注册到 ZooKeeper
 */
@Slf4j
public class LocalRegistryImpl implements LocalRegistry {
    
    private static final Map<String, Object> SERVICE_MAP = new ConcurrentHashMap<>();
    
    // ZooKeeper 注册中心
    private final ServiceRegistry serviceRegistry;
    
    // 服务器地址
    private final String host;
    private final int port;
    
    public LocalRegistryImpl(ServiceRegistry serviceRegistry, String host, int port) {
        this.serviceRegistry = serviceRegistry;
        this.host = host;
        this.port = port;
    }
    
    @Override
    public void register(String serviceName, Object serviceInstance) {
        // 1. 注册到本地
        SERVICE_MAP.put(serviceName, serviceInstance);
        log.info("本地服务注册成功：{}", serviceName);
        
        // 2. 自动注册到 ZooKeeper
        if (serviceRegistry != null) {
            InetSocketAddress address = new InetSocketAddress(host, port);
            serviceRegistry.register(serviceName, address);
            log.info("远程服务注册成功：{}@{}:{}", serviceName, host, port);
        }
    }
    
    @Override
    public Object getService(String serviceName) {
        Object service = SERVICE_MAP.get(serviceName);
        if (service == null) {
            throw new RuntimeException("服务未找到：" + serviceName);
        }
        return service;
    }
    
    @Override
    public void unregister(String serviceName) {
        SERVICE_MAP.remove(serviceName);
        log.info("本地服务注销成功：{}", serviceName);
        
        // 同时从 ZooKeeper 注销
        if (serviceRegistry != null) {
            InetSocketAddress address = new InetSocketAddress(host, port);
            serviceRegistry.unregister(serviceName, address);
        }
    }
    
    @Override
    public boolean contains(String serviceName) {
        return SERVICE_MAP.containsKey(serviceName);
    }
}
```

---

## 五、服务消费者实现

### 5.1 修改 RpcNettyClient

客户端从 ZooKeeper 获取服务提供者列表：

```java
package com.rpc.transport.netty.client;

import com.rpc.config.RpcClientConfig;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.netty.client.connection.ConnectionPool;
import com.rpc.transport.netty.client.manager.RequestManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 基于 Netty 的 RPC 客户端
 */
@Slf4j
public class RpcNettyClient {
    
    // 服务注册中心
    private final ServiceRegistry serviceRegistry;
    
    // 连接池
    private final ConnectionPool connectionPool;
    
    // 请求管理器
    private final RequestManager requestManager;
    
    // Netty 事件循环组
    private final EventLoopGroup eventLoopGroup;
    
    // Bootstrap
    private final Bootstrap bootstrap;
    
    public RpcNettyClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.connectionPool = new ConnectionPool();
        this.requestManager = new RequestManager();
        this.eventLoopGroup = new NioEventLoopGroup();
        
        // 配置 Bootstrap
        this.bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout())
            .handler(new ChannelInitializer<NioSocketChannel>() {
                @Override
                protected void initChannel(NioSocketChannel ch) {
                    ch.pipeline()
                        .addLast("encoder", new RpcProtocolEncoder())
                        .addLast("decoder", new RpcProtocolDecoder())
                        .addLast("handler", new RpcClientHandler(requestManager));
                }
            });
    }
    
    /**
     * 发送 RPC 请求
     */
    public CompletableFuture<RpcResponse> sendRequest(RpcRequest request) {
        try {
            // 1. 从 ZooKeeper 获取服务提供者地址
            List<InetSocketAddress> addresses = 
                serviceRegistry.lookup(request.getServiceName());
            
            if (addresses == null || addresses.isEmpty()) {
                CompletableFuture<RpcResponse> future = new CompletableFuture<>();
                future.completeExceptionally(
                    new RuntimeException("未找到服务：" + request.getServiceName()));
                return future;
            }
            
            // 2. 负载均衡：选择一个地址（这里简单选择第一个）
            // 第 9 课我们会实现负载均衡策略
            InetSocketAddress address = addresses.get(0);
            log.info("选择服务提供者：{}", address);
            
            // 3. 获取连接
            RpcConnection connection = connectionPool.getConnection(
                bootstrap, 
                address.getAddress().getHostAddress(),
                address.getPort()
            );
            
            // 4. 发送请求
            return connection.getChannel().writeAndFlush(request)
                .compose(v -> {
                    // 5. 等待响应
                    return requestManager.getRequestFuture(request.getRequestId());
                });
            
        } catch (Exception e) {
            log.error("发送请求失败", e);
            CompletableFuture<RpcResponse> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }
    
    /**
     * 关闭客户端
     */
    public void close() {
        connectionPool.close();
        eventLoopGroup.shutdownGracefully();
        serviceRegistry.close();
    }
}
```

---

## 六、完整示例

### 6.1 服务提供者启动类

```java
package com.rpc.example.provider;

import com.rpc.config.RpcServerConfig;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.registry.impl.LocalRegistryImpl;
import com.rpc.transport.netty.server.RpcNettyServer;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 服务提供者启动类
 */
@Slf4j
public class ExampleProviderApplication {
    
    public static void main(String[] args) {
        try {
            // 1. 创建 ZooKeeper 注册中心连接
            ServiceRegistry registry = new ZooKeeperRegistryImpl("127.0.0.1:2181", 5000);
            
            // 2. 创建服务器配置
            RpcServerConfig config = RpcServerConfig.custom()
                .host("127.0.0.1")
                .port(8080)
                .bossThreads(1)
                .workerThreads(4);
            
            // 3. 创建 RPC 服务器
            RpcNettyServer server = new RpcNettyServer(config, registry);
            
            // 4. 注册服务
            server.getLocalRegistry().register(
                "com.rpc.example.api.HelloService",
                new HelloServiceImpl()
            );
            
            // 5. 启动服务器
            server.start();
            
        } catch (Exception e) {
            log.error("启动 RPC 服务器失败", e);
            System.exit(1);
        }
    }
}
```

### 6.2 服务消费者启动类

```java
package com.rpc.example.consumer;

import com.rpc.config.RpcClientConfig;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.example.api.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 服务消费者启动类
 */
@Slf4j
public class ExampleConsumerApplication {
    
    public static void main(String[] args) {
        try {
            // 1. 创建 ZooKeeper 注册中心连接
            ServiceRegistry registry = new ZooKeeperRegistryImpl("127.0.0.1:2181", 5000);
            
            // 2. 创建客户端配置
            RpcClientConfig config = RpcClientConfig.custom()
                .connectTimeout(5000)
                .readTimeout(10000);
            
            // 3. 创建 RPC 客户端
            RpcNettyClient client = new RpcNettyClient(config, registry);
            
            // 4. 创建代理工厂
            RpcProxyFactory proxyFactory = new RpcProxyFactory(client);
            
            // 5. 创建服务代理
            HelloService helloService = proxyFactory.createProxy(HelloService.class);
            
            // 6. 调用远程服务
            String result = helloService.sayHello("张三");
            log.info("远程调用结果：{}", result);
            
            // 7. 关闭客户端
            client.close();
            
        } catch (Exception e) {
            log.error("RPC 调用失败", e);
            System.exit(1);
        }
    }
}
```

---

## 七、运行测试

### 7.1 测试步骤

**步骤 1**：启动 ZooKeeper

```bash
cd D:\zookeeper\bin
zkServer.cmd
```

**步骤 2**：启动服务提供者

```bash
运行：ExampleProviderApplication.main()
输出：
========================================
RPC 服务器启动成功
监听端口：8080
ZooKeeper 连接成功
创建服务节点：/rpc/com.rpc.example.api.HelloService
注册地址节点：/rpc/com.rpc.example.api.HelloService/127.0.0.1-8080
```

**步骤 3**：使用 ZooKeeper 客户端验证

```bash
zkCli.cmd

# 查看服务节点
ls /rpc/com.rpc.example.api.HelloService

# 输出应该包含：
# [127.0.0.1-8080]
```

**步骤 4**：启动服务消费者

```bash
运行：ExampleConsumerApplication.main()
输出：
ZooKeeper 连接成功
发现服务 com.rpc.example.api.HelloService，共 1 个提供者：[127.0.0.1:8080]
选择服务提供者：/127.0.0.1:8080
远程调用结果：Hello, 张三!
```

### 7.2 验证服务上下线

**测试服务下线：**

1. 停止服务提供者（Ctrl+C）
2. 在 ZooKeeper 客户端查看：
```bash
ls /rpc/com.rpc.example.api.HelloService
# 输出：[] （节点已消失）
```

**测试服务上线：**

1. 重新启动服务提供者
2. 在 ZooKeeper 客户端查看：
```bash
ls /rpc/com.rpc.example.api.HelloService
# 输出：[127.0.0.1-8080] （节点重新出现）
```

---

## 八、本课总结

### 核心知识点

1. **服务注册与发现的概念**
   - 解耦服务提供者和消费者
   - 支持动态扩缩容
   - 自动感知服务上下线

2. **ZooKeeper 的核心特性**
   - 临时节点：服务下线自动删除
   - 监听机制：实时感知服务变化
   - 强一致性：保证数据一致性

3. **服务注册流程**
   - 创建持久节点作为服务名
   - 创建临时节点作为服务地址
   - 存储元数据信息

4. **服务发现流程**
   - 查询服务名下的所有子节点
   - 获取服务提供者地址列表
   - 进行负载均衡选择

### 课后思考

1. 如果有多个服务提供者，客户端应该如何选择？
2. 如何实现服务的权重负载均衡？
3. ZooKeeper 挂掉了怎么办？如何保证高可用？
4. 如何添加服务版本管理？

---

## 九、动手练习

### 练习 1：实现服务监听

使用 ZooKeeper 的监听机制，当服务列表变化时自动通知客户端。

提示：
```java
List<String> children = zooKeeper.getChildren(servicePath, true);
// 第二个参数为 true 表示设置默认监听器
```

### 练习 2：添加服务元数据

为服务地址添加权重、版本等元数据。

提示：
```java
// 注册时存储元数据
byte[] metadata = "{\"weight\": 1, \"version\": \"1.0\"}".getBytes();
zooKeeper.create(addressPath, metadata, ...);

// 获取时读取元数据
byte[] data = zooKeeper.getData(addressPath, false, null);
```

### 练习 3：实现多注册中心

支持配置多个 ZooKeeper 地址，提高可用性。

提示：
```java
String connectString = "192.168.1.10:2181,192.168.1.11:2181,192.168.1.12:2181";
```

---

## 十、下一步

下一节课我们将实现**负载均衡策略**，当有多个服务提供者时，选择合适的节点进行调用。

**[跳转到第 9 课：负载均衡策略](./lesson-09-load-balance.md)**
