# RPC 框架项目结构说明

## 项目模块划分

```
my-rpc-framework/
├── rpc-core/              # 核心模块（序列化、协议、注册中心等）
├── rpc-server/            # 服务端模块（Netty 服务端实现）
├── rpc-client/            # 客户端模块（Netty 客户端实现）
├── example-api/           # 示例接口定义
├── example-provider/      # 服务提供者示例
└── example-consumer/      # 服务消费者示例
```

## 各模块职责

### 1. rpc-core（核心模块）
- **职责**：包含 RPC 框架的核心功能
- **主要内容**：
  - 序列化/反序列化（Kryo、JSON 等）
  - 协议定义（请求/响应对象）
  - 服务注册与发现（ZooKeeper 集成）
  - 负载均衡策略
  - 公共工具和常量

### 2. rpc-server（服务端模块）
- **职责**：Netty 服务端实现
- **主要内容**：
  - Netty 服务端启动器
  - 请求处理器（Handler）
  - 服务注册表管理
  - 反射调用服务方法

### 3. rpc-client（客户端模块）
- **职责**：Netty 客户端实现
- **主要内容**：
  - Netty 客户端启动器
  - 连接管理
  - 请求发送和响应接收
  - 超时和重试机制

### 4. example-api（示例接口定义）
- **职责**：定义服务接口（供提供者和消费者依赖）
- **主要内容**：
  - 服务接口定义
  - 数据传输对象（DTO）
  - 枚举和常量

### 5. example-provider（服务提供者示例）
- **职责**：服务提供者示例代码
- **主要内容**：
  - 服务接口实现
  - 服务端启动类
  - 服务注册示例

### 6. example-consumer（服务消费者示例）
- **职责**：服务消费者示例代码
- **主要内容**：
  - 客户端启动类
  - 服务发现和调用示例
  - 集成测试

## 模块依赖关系

```
example-provider → rpc-server → rpc-core
example-consumer → rpc-client → rpc-core
example-api → (无依赖)
rpc-server → rpc-core
rpc-client → rpc-core
rpc-core → (基础依赖：Netty, ZooKeeper, Kryo 等)
```

## 技术栈

- **网络通信**：Netty 4.1.94.Final（NIO）
- **服务注册**：ZooKeeper 3.8.4
- **序列化**：Kryo 5.5.0
- **简化代码**：Lombok 1.18.30
- **日志**：SLF4J 2.0.9 + Logback 1.4.11
- **测试**：JUnit 4.13.2

## 快速开始

### 前置条件
- JDK 11 或以上版本
- Maven 3.6+
- ZooKeeper 3.5+

### 编译项目
```bash
mvn clean install
```

### 运行示例
1. 启动 ZooKeeper
2. 运行 `example-provider` 模块的启动类
3. 运行 `example-consumer` 模块的启动类

## 后续开发计划

按照课程文档逐步实现：
- [ ] 第 2 课：动态代理 - RPC 客户端的核心
- [ ] 第 3 课：序列化方案对比与实现
- [ ] 第 4 课：Netty 基础
- [ ] 第 5 课：RPC 协议设计
- [ ] 第 6 课：Netty 服务端实现
- [ ] 第 7 课：Netty 客户端实现
