# 第 9 课：负载均衡策略

## 学习目标

- 理解负载均衡在 RPC 框架中的作用
- 掌握常见的负载均衡算法原理
- 实现随机、轮询、最少连接数等负载均衡策略
- 理解一致性 Hash 算法的原理和实现
- 学会使用 SPI 机制扩展负载均衡策略

---

## 一、为什么需要负载均衡？

### 1.1 场景引入

在第 8 课中，我们实现了服务注册与发现。当一个服务有多个提供者时，客户端应该选择哪一个？

```
                  Consumer
                     │
                     ↓ 选择哪个？
         ┌───────────┼───────────┐
         │           │           │
    Provider A   Provider B   Provider C
    (192.168.1.10) (192.168.1.11) (192.168.1.12)
```

**问题：**
- 如果每次都选第一个，其他提供者会闲置
- 如果随机选，可能某些提供者负载过高
- 如何根据提供者的处理能力分配请求？

### 1.2 负载均衡的作用

```
┌─────────────────────────────────────────────────────────┐
│                  负载均衡架构                            │
├─────────────────────────────────────────────────────────┤
│                                                         │
│   客户端                                               │
│     │                                                   │
│     ↓ 1. 查询服务                                        │
│   Registry                                              │
│     │                                                   │
│     ↓ 2. 返回 [A, B, C]                                 │
│   LoadBalancer                                          │
│     │                                                   │
│     ↓ 3. 根据策略选择一个                               │
│   Provider B ✓                                          │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**核心目标：**
1. **均匀分配**：将请求均匀分配到多个服务节点
2. **快速响应**：选择当前最快的节点
3. **高可用**：避免单点过载，提高系统稳定性
4. **可扩展**：支持动态添加/删除节点

---

## 二、常见负载均衡算法

### 2.1 随机策略（Random）

**原理：** 从服务列表中随机选择一个节点

```java
public class RandomLoadBalancer {
    private final Random random = new Random();
    
    public <T> T select(List<T> list) {
        if (list.isEmpty()) {
            return null;
        }
        int index = random.nextInt(list.size());
        return list.get(index);
    }
}
```

**优点：**
- ✅ 实现简单
- ✅ 无需维护状态
- ✅ 请求分布均匀（大样本下）

**缺点：**
- ❌ 不考虑节点性能差异
- ❌ 可能连续选中同一节点

**适用场景：**
- 节点性能相近
- 请求量不大

---

### 2.2 轮询策略（Round Robin）

**原理：** 依次轮流选择每个节点

```java
public class RoundRobinLoadBalancer {
    private final AtomicInteger index = new AtomicInteger(0);
    
    public <T> T select(List<T> list) {
        if (list.isEmpty()) {
            return null;
        }
        int currentIndex = index.getAndIncrement() % list.size();
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        return list.get(currentIndex);
    }
}
```

**执行过程：**
```
请求 1 → Provider A
请求 2 → Provider B
请求 3 → Provider C
请求 4 → Provider A
请求 5 → Provider B
...
```

**优点：**
- ✅ 绝对均衡
- ✅ 实现简单
- ✅ 无需监控节点状态

**缺点：**
- ❌ 不考虑节点性能
- ❌ 慢节点会拖慢整体

**适用场景：**
- 节点性能相近
- 请求处理时间相近

---

### 2.3 加权轮询策略（Weighted Round Robin）

**原理：** 根据节点权重分配请求比例

```java
public class WeightedRoundRobinLoadBalancer {
    
    static class Server {
        String address;
        int weight;      // 权重
        int currentWeight; // 当前权重
        
        Server(String address, int weight) {
            this.address = address;
            this.weight = weight;
        }
    }
    
    private final List<Server> servers = new ArrayList<>();
    
    public void addServer(String address, int weight) {
        servers.add(new Server(address, weight));
    }
    
    public String select() {
        if (servers.isEmpty()) {
            return null;
        }
        
        int totalWeight = 0;
        int maxWeight = 0;
        
        // 1. 计算总权重和最大权重
        for (Server server : servers) {
            totalWeight += server.weight;
            maxWeight = Math.max(maxWeight, server.weight);
            
            // 2. 增加当前权重
            server.currentWeight += server.weight;
        }
        
        // 3. 选择当前权重最大的节点
        Server selected = null;
        for (Server server : servers) {
            if (selected == null || server.currentWeight > selected.currentWeight) {
                selected = server;
            }
        }
        
        // 4. 减少选中节点的当前权重
        selected.currentWeight -= totalWeight;
        
        return selected.address;
    }
}
```

**执行示例：**
```
服务器权重：
  A: 5
  B: 3
  C: 2

10 次请求的分配：
  A, B, A, C, A, B, A, C, A, B
  ↑ 5 次   ↑ 3 次   ↑ 2 次
```

**优点：**
- ✅ 考虑节点性能差异
- ✅ 按比例分配请求

**缺点：**
- ❌ 需要预先配置权重
- ❌ 不能动态调整

**适用场景：**
- 节点性能有差异
- 可以预估处理能力

---

### 2.4 最少连接数策略（Least Connections）

**原理：** 选择当前活跃连接数最少的节点

```java
public class LeastConnectionsLoadBalancer {
    
    static class Server {
        String address;
        AtomicInteger connections = new AtomicInteger(0);
        
        Server(String address) {
            this.address = address;
        }
        
        void addConnection() {
            connections.incrementAndGet();
        }
        
        void removeConnection() {
            connections.decrementAndGet();
        }
    }
    
    private final Map<String, Server> serverMap = new ConcurrentHashMap<>();
    
    public void addServer(String address) {
        serverMap.putIfAbsent(address, new Server(address));
    }
    
    public String select() {
        if (serverMap.isEmpty()) {
            return null;
        }
        
        Server selected = null;
        int minConnections = Integer.MAX_VALUE;
        
        for (Server server : serverMap.values()) {
            int conn = server.connections.get();
            if (conn < minConnections) {
                minConnections = conn;
                selected = server;
            }
        }
        
        // 增加连接数
        if (selected != null) {
            selected.addConnection();
        }
        
        return selected != null ? selected.address : null;
    }
    
    public void releaseConnection(String address) {
        Server server = serverMap.get(address);
        if (server != null) {
            server.removeConnection();
        }
    }
}
```

**优点：**
- ✅ 自适应负载
- ✅ 优先选择空闲节点
- ✅ 适合长连接场景

**缺点：**
- ❌ 需要维护连接数
- ❌ 实现相对复杂

**适用场景：**
- 长连接、耗时操作
- 节点性能差异大

---

### 2.5 一致性 Hash 策略（Consistent Hashing）

**原理：** 将服务和请求映射到哈希环上，顺时针找到最近的节点

```java
public class ConsistentHashLoadBalancer {
    
    // 虚拟节点数量（解决数据倾斜）
    private static final int VIRTUAL_NODES = 160;
    
    // 哈希环：key=虚拟节点 hash 值，value=真实服务器地址
    private final TreeMap<Integer, String> circle = new TreeMap<>();
    
    /**
     * 添加服务器节点
     */
    public void addServer(String address) {
        // 为每个真实节点创建多个虚拟节点
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualNodeName = address + "#" + i;
            int hash = hash(virtualNodeName);
            circle.put(hash, address);
        }
    }
    
    /**
     * 移除服务器节点
     */
    public void removeServer(String address) {
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualNodeName = address + "#" + i;
            int hash = hash(virtualNodeName);
            circle.remove(hash);
        }
    }
    
    /**
     * 选择服务器
     */
    public String select(String key) {
        if (circle.isEmpty()) {
            return null;
        }
        
        // 1. 计算请求的 hash 值
        int hash = hash(key);
        
        // 2. 在环上顺时针查找第一个节点
        SortedMap<Integer, String> tailMap = circle.tailMap(hash);
        
        // 3. 如果后面没有节点，就选择环的第一个节点
        Integer nodeHash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        
        return circle.get(nodeHash);
    }
    
    /**
     * Hash 算法（可以使用 MD5、MurmurHash 等）
     */
    private int hash(String key) {
        // 简化版本：使用 MurmurHash 算法
        return murmurHash(key.getBytes(StandardCharsets.UTF_8));
    }
    
    private int murmurHash(byte[] data) {
        // MurmurHash2 实现（简化版）
        int len = data.length;
        int seed = 0x1234ABCD;
        int m = 0x5BD1E995;
        int r = 24;
        
        int h = seed ^ len;
        int len4 = len / 4;
        
        for (int i = 0; i < len4; i++) {
            int i4 = i * 4;
            int k = (data[i4] & 0xff) + ((data[i4 + 1] & 0xff) << 8)
                  + ((data[i4 + 2] & 0xff) << 16) + ((data[i4 + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        
        // Handle the last few bytes
        int offset = len4 * 4;
        switch (len - offset) {
            case 3: h ^= (data[offset + 2] & 0xff) << 16;
            case 2: h ^= (data[offset + 1] & 0xff) << 8;
            case 1: h ^= (data[offset] & 0xff);
                    h *= m;
        }
        
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        
        return h;
    }
}
```

**可视化说明：**
```
          哈希环
      ↗¯¯¯¯¯¯¯¯¯¯¯↖
     /             \
    |   ○ VN(B#1)   |
    |               |
    ○ VN(A#2)       ○ VN(C#1)
    |               |
    |   ★ Request   |
    |      ↓        |
    |   ○ VN(A#1)   |
     \             /
      ↘___________↙

请求 → 顺时针找到的第一个节点 → Provider A
```

**为什么需要虚拟节点？**
```
不使用虚拟节点：
  A ────── B ────── C
  ↑        ↑        ↑
  50%     25%      25%  ← 数据倾斜！

使用虚拟节点（每个节点 10 个虚拟节点）：
  A1,B1,C1,A2,B2,C2,A3,B3,C3,... (均匀分布在环上)
  ↑ 各占约 33.3% ← 分布均匀！
```

**优点：**
- ✅ 节点增减影响最小
- ✅ 相同 key 总是路由到同一节点（适合缓存）
- ✅ 天然支持分布式

**缺点：**
- ❌ 实现复杂
- ❌ 需要虚拟节点解决倾斜问题

**适用场景：**
- 需要会话保持
- 分布式缓存
- 节点频繁上下线

---

## 三、统一负载均衡接口

定义统一的接口，方便扩展和切换：

```java
package com.rpc.loadbalance;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 负载均衡器接口
 */
public interface LoadBalancer {
    
    /**
     * 从服务列表中选择一个节点
     * @param serviceName 服务名称
     * @param addresses 服务地址列表
     * @return 选中的地址
     */
    InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses);
    
    /**
     * 获取负载均衡策略名称
     */
    String getName();
}
```

### 3.1 随机策略实现

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;

/**
 * 随机负载均衡策略
 */
public class RandomLoadBalancer implements LoadBalancer {
    
    private final Random random = new Random();
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        int index = random.nextInt(addresses.size());
        InetSocketAddress selected = addresses.get(index);
        
        System.out.println("[Random] 选择：" + selected);
        return selected;
    }
    
    @Override
    public String getName() {
        return "random";
    }
}
```

### 3.2 轮询策略实现

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡策略
 */
public class RoundRobinLoadBalancer implements LoadBalancer {
    
    // 每个服务维护一个轮询计数器
    private final ConcurrentHashMap<String, AtomicInteger> counters = 
        new ConcurrentHashMap<>();
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        // 获取或创建计数器
        AtomicInteger counter = counters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        
        // 轮询选择
        int index = Math.abs(counter.getAndIncrement() % addresses.size());
        InetSocketAddress selected = addresses.get(index);
        
        System.out.println("[RoundRobin] 选择：" + selected);
        return selected;
    }
    
    @Override
    public String getName() {
        return "roundrobin";
    }
}
```

### 3.3 最少连接数策略实现

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最少连接数负载均衡策略
 */
@Slf4j
public class LeastConnectionsLoadBalancer implements LoadBalancer {
    
    // 记录每个地址的连接数
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        // 找到连接数最少的地址
        InetSocketAddress selected = null;
        int minConnections = Integer.MAX_VALUE;
        
        for (InetSocketAddress address : addresses) {
            String addrKey = addressToString(address);
            AtomicInteger count = connectionCounts.computeIfAbsent(addrKey, k -> new AtomicInteger(0));
            int connections = count.get();
            
            if (connections < minConnections) {
                minConnections = connections;
                selected = address;
            }
        }
        
        // 增加选中地址的连接数
        if (selected != null) {
            String addrKey = addressToString(selected);
            connectionCounts.computeIfAbsent(addrKey, k -> new AtomicInteger(0)).incrementAndGet();
            log.debug("[LeastConnections] 选择：{} (当前连接数：{})", 
                selected, connectionCounts.get(addrKey).get());
        }
        
        return selected;
    }
    
    /**
     * 释放连接（RPC 调用完成后调用）
     */
    public void releaseConnection(InetSocketAddress address) {
        if (address != null) {
            String addrKey = addressToString(address);
            AtomicInteger count = connectionCounts.get(addrKey);
            if (count != null) {
                count.decrementAndGet();
                log.debug("[LeastConnections] 释放连接：{} (剩余连接数：{})", 
                    address, count.get());
            }
        }
    }
    
    @Override
    public String getName() {
        return "leastconnections";
    }
    
    private String addressToString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }
}
```

### 3.4 一致性 Hash 策略实现

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一致性 Hash 负载均衡策略
 */
@Slf4j
public class ConsistentHashLoadBalancer implements LoadBalancer {
    
    // 每个服务一个哈希环
    private final ConcurrentHashMap<String, TreeMap<Integer, String>> rings = 
        new ConcurrentHashMap<>();
    
    // 虚拟节点数量
    private static final int VIRTUAL_NODES = 160;
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        // 获取或创建该服务的哈希环
        TreeMap<Integer, String> ring = rings.computeIfAbsent(serviceName, k -> {
            TreeMap<Integer, String> newRing = new TreeMap<>();
            // 初始化哈希环
            for (InetSocketAddress address : addresses) {
                addVirtualNodes(newRing, address);
            }
            return newRing;
        });
        
        // 如果环为空，重新构建
        if (ring.isEmpty()) {
            for (InetSocketAddress address : addresses) {
                addVirtualNodes(ring, address);
            }
        }
        
        // 使用服务名作为 hash key（也可以使用方法名等）
        int hash = murmurHash(serviceName.getBytes(StandardCharsets.UTF_8));
        
        // 顺时针查找
        SortedMap<Integer, String> tailMap = ring.tailMap(hash);
        Integer nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        
        String selectedAddress = ring.get(nodeHash);
        InetSocketAddress selected = stringToAddress(selectedAddress);
        
        log.debug("[ConsistentHash] 选择：{}", selected);
        return selected;
    }
    
    /**
     * 添加虚拟节点
     */
    private void addVirtualNodes(TreeMap<Integer, String> ring, InetSocketAddress address) {
        String addressStr = addressToString(address);
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualNodeName = addressStr + "#" + i;
            int hash = murmurHash(virtualNodeName.getBytes(StandardCharsets.UTF_8));
            ring.put(hash, addressStr);
        }
    }
    
    @Override
    public String getName() {
        return "consistenthash";
    }
    
    // ==================== 辅助方法 ====================
    
    private int murmurHash(byte[] data) {
        int len = data.length;
        int seed = 0x1234ABCD;
        int m = 0x5BD1E995;
        int r = 24;
        
        int h = seed ^ len;
        int len4 = len / 4;
        
        for (int i = 0; i < len4; i++) {
            int i4 = i * 4;
            int k = (data[i4] & 0xff) + ((data[i4 + 1] & 0xff) << 8)
                  + ((data[i4 + 2] & 0xff) << 16) + ((data[i4 + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        
        int offset = len4 * 4;
        switch (len - offset) {
            case 3: h ^= (data[offset + 2] & 0xff) << 16;
            case 2: h ^= (data[offset + 1] & 0xff) << 8;
            case 1: h ^= (data[offset] & 0xff);
                    h *= m;
        }
        
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        
        return h;
    }
    
    private String addressToString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }
    
    private InetSocketAddress stringToAddress(String addressStr) {
        String[] parts = addressStr.split(":");
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}
```

---

## 四、整合到 RPC 框架

### 4.1 修改 RpcClientConfig

添加负载均衡配置：

```java
package com.rpc.config;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.loadbalance.impl.RandomLoadBalancer;
import lombok.Builder;
import lombok.Data;

/**
 * RPC 客户端配置
 */
@Data
@Builder
public class RpcClientConfig {
    
    // 连接超时时间（毫秒）
    private int connectTimeout = 5000;
    
    // 读取超时时间（毫秒）
    private int readTimeout = 10000;
    
    // 负载均衡器
    @Builder.Default
    private LoadBalancer loadBalancer = new RandomLoadBalancer();
    
    // 重试次数
    @Builder.Default
    private int retryTimes = 3;
    
    public static RpcClientConfig custom() {
        return RpcClientConfig.builder().build();
    }
}
```

### 4.2 修改 RpcNettyClient

集成负载均衡：

```java
package com.rpc.transport.netty.client;

import com.rpc.config.RpcClientConfig;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.registry.ServiceRegistry;
import com.rpc.transport.netty.client.connection.ConnectionPool;
import com.rpc.transport.netty.client.manager.RequestManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
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
    
    // 负载均衡器
    private final LoadBalancer loadBalancer;
    
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
        this.loadBalancer = config.getLoadBalancer();
        this.connectionPool = new ConnectionPool();
        this.requestManager = new RequestManager();
        this.eventLoopGroup = new NioEventLoopGroup();
        
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
            // 1. 从注册中心获取服务提供者列表
            List<InetSocketAddress> addresses = 
                serviceRegistry.lookup(request.getServiceName());
            
            if (addresses == null || addresses.isEmpty()) {
                CompletableFuture<RpcResponse> future = new CompletableFuture<>();
                future.completeExceptionally(
                    new RuntimeException("未找到服务：" + request.getServiceName()));
                return future;
            }
            
            // 2. 【关键】使用负载均衡器选择一个节点
            InetSocketAddress address = loadBalancer.select(
                request.getServiceName(), 
                addresses
            );
            
            if (address == null) {
                CompletableFuture<RpcResponse> future = new CompletableFuture<>();
                future.completeExceptionally(
                    new RuntimeException("负载均衡选择失败"));
                return future;
            }
            
            log.info("负载均衡选择服务提供者：{}", address);
            
            // 3. 获取连接并发送请求
            RpcConnection connection = connectionPool.getConnection(
                bootstrap, 
                address.getAddress().getHostAddress(),
                address.getPort()
            );
            
            return connection.getChannel().writeAndFlush(request)
                .compose(v -> requestManager.getRequestFuture(request.getRequestId()));
            
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

## 五、测试验证

### 5.1 准备多个服务提供者

启动多个服务实例：

```java
// Provider 1 - 端口 8080
RpcServerConfig config1 = RpcServerConfig.custom()
    .port(8080);
RpcNettyServer server1 = new RpcNettyServer(config1, registry);
server1.getLocalRegistry().register("HelloService", new HelloServiceImpl());
server1.start();

// Provider 2 - 端口 8081
RpcServerConfig config2 = RpcServerConfig.custom()
    .port(8081);
RpcNettyServer server2 = new RpcNettyServer(config2, registry);
server2.getLocalRegistry().register("HelloService", new HelloServiceImpl());
server2.start();

// Provider 3 - 端口 8082
RpcServerConfig config3 = RpcServerConfig.custom()
    .port(8082);
RpcNettyServer server3 = new RpcNettyServer(config3, registry);
server3.getLocalRegistry().register("HelloService", new HelloServiceImpl());
server3.start();
```

### 5.2 测试不同负载均衡策略

```java
public class LoadBalanceTest {
    
    @Test
    public void testRandom() throws Exception {
        RpcClientConfig config = RpcClientConfig.custom()
            .loadBalancer(new RandomLoadBalancer());
        
        RpcNettyClient client = new RpcNettyClient(config, registry);
        HelloService proxy = proxyFactory.createProxy(HelloService.class);
        
        // 调用 10 次，观察分布
        for (int i = 0; i < 10; i++) {
            String result = proxy.sayHello("User" + i);
            System.out.println(result);
        }
        
        client.close();
    }
    
    @Test
    public void testRoundRobin() throws Exception {
        RpcClientConfig config = RpcClientConfig.custom()
            .loadBalancer(new RoundRobinLoadBalancer());
        
        // ... 同上
    }
    
    @Test
    public void testLeastConnections() throws Exception {
        RpcClientConfig config = RpcClientConfig.custom()
            .loadBalancer(new LeastConnectionsLoadBalancer());
        
        // ... 同上
    }
}
```

### 5.3 预期输出

**随机策略：**
```
[Random] 选择：/127.0.0.1:8081
[Random] 选择：/127.0.0.1:8080
[Random] 选择：/127.0.0.1:8081
[Random] 选择：/127.0.0.1:8082
... (无明显规律)
```

**轮询策略：**
```
[RoundRobin] 选择：/127.0.0.1:8080
[RoundRobin] 选择：/127.0.0.1:8081
[RoundRobin] 选择：/127.0.0.1:8082
[RoundRobin] 选择：/127.0.0.1:8080
[RoundRobin] 选择：/127.0.0.1:8081
... (按顺序循环)
```

**最少连接数策略：**
```
[LeastConnections] 选择：/127.0.0.1:8080 (当前连接数：1)
[LeastConnections] 选择：/127.0.0.1:8081 (当前连接数：1)
[LeastConnections] 选择：/127.0.0.1:8082 (当前连接数：1)
... (趋向于平衡)
```

---

## 六、本课总结

### 核心知识点

1. **负载均衡的作用**
   - 均匀分配请求
   - 提高系统可用性
   - 优化资源利用

2. **常见负载均衡算法**
   - 随机：简单随机选择
   - 轮询：依次轮流选择
   - 加权轮询：按权重比例分配
   - 最少连接数：选择最空闲的节点
   - 一致性 Hash：保持会话亲和性

3. **算法选择原则**
   - 节点性能相近 → 随机/轮询
   - 节点性能差异 → 加权轮询/最少连接数
   - 需要会话保持 → 一致性 Hash
   - 长连接场景 → 最少连接数

### 课后思考

1. 如何实现动态权重调整（根据节点实时负载）？
2. 一致性 Hash 中，如果有节点宕机，如何最小化影响？
3. 如何结合多种策略（如：先最少连接，再随机）？
4. 如何监控负载均衡的效果？

---

## 七、动手练习

### 练习 1：实现加权随机策略

根据节点权重进行随机分配。

提示：
```java
// 权重越大，被选中的概率越高
int totalWeight = weights.stream().mapToInt(w -> w).sum();
int random = new Random().nextInt(totalWeight);
// 根据 random 值落在哪个区间决定选择哪个节点
```

### 练习 2：实现 P2P 负载均衡

每个客户端只连接部分节点，节点之间互相转发。

### 练习 3：添加健康检查

在选择节点前，先检查节点是否健康。

提示：
```java
public class HealthCheckLoadBalancer implements LoadBalancer {
    // 定期 ping 节点
    // 过滤掉不健康的节点
    // 从健康节点中选择
}
```

---

## 八、下一步

下一节课我们将实现**心跳检测与断线重连**机制，保证连接的可靠性。

**[跳转到第 10 课：心跳检测与断线重连](./lesson-10-heartbeat.md)**
