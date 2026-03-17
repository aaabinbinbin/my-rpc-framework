# 第 1 课：RPC 基础概念与环境搭建

## 学习目标

- 理解什么是 RPC 以及 RPC 的工作原理
- 搭建 RPC 框架开发环境
- 配置 Maven 依赖
- 了解项目整体结构

---

## 一、什么是 RPC？

### 1.1 RPC 的定义

**RPC（Remote Procedure Call，远程过程调用）** 是一种计算机通信协议，它允许一个程序调用另一个地址空间（通常是另一台机器上）的过程或函数，而无需程序员显式地为这个远程调用编写底层网络通信代码。

**简单来说**：RPC 让你像调用本地方法一样调用远程服务器上的方法。

### 1.2 为什么需要 RPC？

在分布式系统中，不同的服务运行在不同的服务器上，服务之间需要相互调用。如果每次都手动编写网络通信代码（Socket 编程、序列化、反序列化等），会非常繁琐且容易出错。

**没有 RPC 之前**：
```java
// 客户端需要手动处理所有底层细节
Socket socket = new Socket("server.com", 8080);
OutputStream os = socket.getOutputStream();
// 手动序列化参数
byte[] data = serialize(methodName, params);
os.write(data);
// 等待响应并反序列化
InputStream is = socket.getInputStream();
byte[] result = readResponse(is);
Object response = deserialize(result);
```

**使用 RPC 之后**：
```java
// 像调用本地方法一样简单
HelloService service = RpcProxy.create(HelloService.class);
String result = service.sayHello("world");
```

### 1.3 RPC 的核心组成部分

一个完整的 RPC 框架包含以下核心组件：

```
┌─────────────────────────────────────────────────────────┐
│                      客户端                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │   服务代理    │  │   编解码器    │  │  网络客户端  │  │
│  │  (Proxy)     │→ │ (Serializer) │→ │  (Client)   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          ↓ 网络请求
┌─────────────────────────────────────────────────────────┐
│                      服务端                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  网络服务端   │  │   编解码器    │  │  服务注册表  │  │
│  │   (Server)   │← │ (Serializer) │← │  (Registry) │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│                          ↓                              │
│                   ┌──────────────┐                      │
│                   │  业务逻辑实现 │                      │
│                   │  (Service)   │                      │
│                   └──────────────┘                      │
└─────────────────────────────────────────────────────────┘
```

**各组件职责**：

1. **服务代理（Proxy）**
   - 拦截客户端的调用请求
   - 封装调用参数
   - 隐藏远程调用的复杂性

2. **编解码器（Serializer/Deserializer）**
   - 序列化：将对象转换为字节流（用于网络传输）
   - 反序列化：将字节流还原为对象
   - 常见的序列化方式：JSON、Protobuf、Kryo、Hessian

3. **网络客户端/服务端（Client/Server）**
   - 建立网络连接
   - 发送/接收数据
   - 管理连接池、超时、重试等

4. **服务注册表（Registry）**
   - 存储服务提供者地址
   - 支持服务发现
   - 负载均衡

---

## 二、RPC 工作原理

### 2.1 RPC 调用流程

```
客户端                                    服务端
  │                                        │
  │  1. 调用代理方法                        │
  │     service.sayHello("world")          │
  │                                        │
  │  2. 代理封装请求参数                    │
  │     (类名、方法名、参数类型、参数值)      │
  │ ─────────────────────────────────────> │
  │           3. 网络传输请求               │
  │                                        │
  │                                        │ 4. 解析请求
  │                                        │    找到对应服务
  │                                        │    反射调用方法
  │                                        │
  │                                        │ 5. 封装响应结果
  │ <───────────────────────────────────── │
  │           6. 网络传输响应               │
  │                                        │
  │  7. 解析响应                            │
  │     返回结果                           │
  │                                        │
```

### 2.2 关键技术点

#### （1）动态代理
- 在运行时动态创建接口的代理对象
- 拦截方法调用，执行远程调用逻辑
- Java 提供了 `java.lang.reflect.Proxy` 和 CGLIB 两种实现方式

#### （2）序列化/反序列化
- 将 Java 对象转换为可传输的字节流
- 要求：体积小、性能好、支持跨语言
- 常见方案对比：
  - **Java 原生序列化**：简单但性能差，不推荐
  - **JSON**：可读性好，但体积较大
  - **Protobuf**：Google 出品，性能好，但需要定义 proto 文件
  - **Kryo**：高性能，适合 Java 应用
  - **Hessian**：轻量级，支持跨语言

#### （3）网络传输
- **BIO**：阻塞 IO，一个连接一个线程，适合连接数少的场景
- **NIO**：非阻塞 IO，多路复用，适合高并发场景 ⭐推荐
- **AIO**：异步 IO，基于事件驱动，适合超高并发但实现复杂

#### （4）服务注册与发现
- 服务启动时向注册中心注册自己的地址
- 客户端从注册中心获取服务提供者地址
- 支持服务上下线感知
- 常用注册中心：ZooKeeper、Nacos、Eureka、Consul

---

## 三、环境搭建

### 3.1 开发环境要求

- **JDK**：1.8 或以上版本（推荐 JDK 11 或 17）
- **Maven**：3.6+ 
- **IDE**：IntelliJ IDEA（推荐）或 Eclipse
- **ZooKeeper**：3.5+（服务注册中心）

### 3.2 安装 ZooKeeper（Windows）

#### 步骤 1：下载 ZooKeeper
访问官网下载：https://zookeeper.apache.org/releases.html

选择最新版本，例如：zookeeper-3.8.4.tar.gz

#### 步骤 2：解压并配置
```bash
# 解压到 D:\zookeeper
# 进入配置目录
D:\zookeeper\conf\

# 复制配置文件
copy zoo_sample.cfg zoo.cfg
```

#### 步骤 3：修改 zoo.cfg
```properties
tickTime=2000
initLimit=10
syncLimit=5
dataDir=D:\\zookeeper\\data
clientPort=2181
```

#### 步骤 4：启动 ZooKeeper
```bash
# 进入 zookeeper 目录
cd D:\zookeeper\bin

# 启动服务端
zkServer.cmd

# 启动客户端（验证是否成功）
zkCli.cmd
```

看到 `Welcome to ZooKeeper!` 表示启动成功！

### 3.3 创建项目结构

我们将项目拆分为多个模块，便于管理和学习：

```
my-rpc-framework/
├── rpc-core/              # 核心模块（序列化、协议、注册中心等）
├── rpc-server/            # 服务端模块
├── rpc-client/            # 客户端模块
├── example-api/           # 示例接口定义
├── example-provider/      # 服务提供者示例
└── example-consumer/      # 服务消费者示例
```

### 3.4 配置父 POM

首先，将当前的 pom.xml 改造为父 POM：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.rpc</groupId>
    <artifactId>my-rpc-framework</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>rpc-core</module>
        <module>rpc-server</module>
        <module>rpc-client</module>
        <module>example-api</module>
        <module>example-provider</module>
        <module>example-consumer</module>
    </modules>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        
        <!-- 依赖版本管理 -->
        <netty.version>4.1.94.Final</netty.version>
        <zk.version>3.8.4</zk.version>
        <kryo.version>5.5.0</kryo.version>
        <lombok.version>1.18.30</lombok.version>
        <slf4j.version>2.0.9</slf4j.version>
        <logback.version>1.4.11</logback.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Netty -->
            <dependency>
                <groupId>io.netty</groupId>
                <artifactId>netty-all</artifactId>
                <version>${netty.version}</version>
            </dependency>

            <!-- ZooKeeper -->
            <dependency>
                <groupId>org.apache.zookeeper</groupId>
                <artifactId>zookeeper</artifactId>
                <version>${zk.version}</version>
            </dependency>

            <!-- Kryo 序列化 -->
            <dependency>
                <groupId>com.esotericsoftware</groupId>
                <artifactId>kryo</artifactId>
                <version>${kryo.version}</version>
            </dependency>

            <!-- Lombok 简化代码 -->
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </dependency>

            <!-- 日志 -->
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j.version}</version>
            </dependency>
            <dependency>
                <groupId>ch.qos.logback</groupId>
                <artifactId>logback-classic</artifactId>
                <version>${logback.version}</version>
            </dependency>

            <!-- 测试 -->
            <dependency>
                <groupId>junit</groupId>
                <artifactId>junit</artifactId>
                <version>4.13.2</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

### 3.5 创建子模块

#### 模块 1：rpc-core（核心模块）
包含：序列化、协议定义、服务注册、负载均衡等核心功能

后续会创建 pom.xml：
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.rpc</groupId>
        <artifactId>my-rpc-framework</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>rpc-core</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- Netty -->
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-all</artifactId>
        </dependency>

        <!-- ZooKeeper -->
        <dependency>
            <groupId>org.apache.zookeeper</groupId>
            <artifactId>zookeeper</artifactId>
        </dependency>

        <!-- Kryo -->
        <dependency>
            <groupId>com.esotericsoftware</groupId>
            <artifactId>kryo</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- 日志 -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

#### 其他模块说明

**rpc-server**：Netty 服务端实现，依赖 rpc-core  
**rpc-client**：Netty 客户端实现，依赖 rpc-core  
**example-api**：定义服务接口（供提供者和消费者依赖）  
**example-provider**：服务提供者示例，依赖 rpc-server 和 example-api  
**example-consumer**：服务消费者示例，依赖 rpc-client 和 example-api  

---

## 四、知识扩展：主流 RPC 框架对比

了解业界优秀的 RPC 框架，可以帮助我们更好地设计自己的框架。

### 4.1 Dubbo（阿里巴巴）

**特点**：
- 国内最流行的 RPC 框架
- 功能全面：服务发现、负载均衡、熔断降级、监控等
- 生态完善：Spring Cloud Alibaba 整合
- 支持多种协议：Dubbo、HTTP、gRPC 等
- 支持多种注册中心：ZooKeeper、Nacos、Redis 等

**适用场景**：企业级微服务架构

### 4.2 gRPC（Google）

**特点**：
- 基于 HTTP/2 和 Protobuf
- 性能卓越，跨语言支持好
- 支持双向流、流量控制等高级特性
- 云原生时代的首选 RPC 框架

**适用场景**：跨语言调用、微服务间通信

### 4.3 Spring Cloud（Pivotal）

**特点**：
- 基于 HTTP 的 RESTful API
- 生态完整：网关、配置中心、断路器等
- 学习成本低，Spring 开发者友好

**适用场景**：Spring 技术栈的微服务项目

### 4.4 Motan（微博）

**特点**：
- 轻量级
- 低延迟
- 易扩展

**适用场景**：高并发互联网应用

---

## 五、本课总结

### 核心知识点

1. **RPC 是什么**：远程过程调用，让你像调用本地方法一样调用远程服务
2. **RPC 的核心组件**：
   - 服务代理（隐藏远程调用细节）
   - 编解码器（序列化和反序列化）
   - 网络传输（客户端和服务端）
   - 服务注册与发现（管理服务地址）
3. **为什么选择这些技术**：
   - **Netty**：高性能、易用、稳定
   - **NIO**：适合高并发场景
   - **ZooKeeper**：成熟稳定的服务注册中心
4. **环境搭建**：
   - 安装并启动 ZooKeeper
   - 配置 Maven 多模块项目

### 课后思考

1. RPC 和本地调用的本质区别是什么？

   **答：RPC可以调用不同进程、不同机器的其他服务，需通过网络传输，调用方将方法名、参数等数据序列化后发送给远程服务，远程服务反序列化后执行实际方法，再将结果序列化返回。**

2. 为什么要使用动态代理？不用可以吗？

   **答：在RPC客户端，通过动态代理生成接口的代理对象，拦截接口方法调用，将调用信息（接口名、方法名、参数类型、参数值）封装成RPC请求，并负责网络传输、等待响应、反序列化结果等。这样对调用方完全屏蔽了底层细节，实现“透明调用”。**

3. 序列化方式有哪些？各自有什么优缺点？

   **答：参考2.2（2）**

4. 为什么选择 NIO 而不是 BIO？

   **答：BIO阻塞，导致线程资源浪费。NIO基于Selector（选择器），一个线程可以管理多个连接，通过事件驱动（读就绪、写就绪）处理I/O，避免了线程阻塞，大大降低了线程资源消耗。**

---

## 六、下一步

在开始下一节课之前，请确保：

- ✅ 已经安装好 JDK 和 Maven
- ✅ 已经安装并启动 ZooKeeper
- ✅ 已经完成项目结构的搭建
- ✅ 理解 RPC 的基本原理

**[跳转到第 2 课：动态代理 - RPC 客户端的核心](./lesson-02-dynamic-proxy.md)**

---

## 附录：常见问题

### Q1：ZooKeeper 启动失败怎么办？
**A**：检查以下几点：
1. 端口 2181 是否被占用
2. zoo.cfg 配置文件是否正确
3. dataDir 目录是否存在
4. 查看 logs 目录下的日志文件

### Q2：Maven 依赖下载慢怎么办？
**A**：配置阿里云镜像，在 `~/.m2/settings.xml` 中添加：
```xml
<mirrors>
    <mirror>
        <id>aliyun</id>
        <name>Aliyun Maven</name>
        <url>https://maven.aliyun.com/repository/public</url>
        <mirrorOf>central</mirrorOf>
    </mirror>
</mirrors>
```

### Q3：为什么要分这么多模块？
**A**：模块化有以下好处：
1. **职责清晰**：每个模块负责特定功能
2. **易于维护**：代码组织更有条理
3. **依赖管理**：避免循环依赖
4. **便于学习**：循序渐进，逐步实现
