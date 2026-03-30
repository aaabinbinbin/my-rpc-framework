# 项目结构小白版说明

## 1. 为什么这个项目目录看起来这么多

第一次看这个仓库，最容易被吓到的地方就是模块多、包多、类也多。

但其实你可以先把它简化成一句话：

`这个仓库分成“框架本体”和“使用示例”两部分。`

## 2. 先看最外层目录

```text
my-rpc-framework
├─ rpc-core
├─ rpc-spring
├─ rpc-spring-boot-starter
├─ example-api
├─ example-provider
├─ example-consumer
└─ doc
```

## 3. 哪些是框架本体

### 3.1 `rpc-core`

这是最核心的模块。

它负责：
1. 定义 RPC 注解
2. 定义协议和请求响应对象
3. 做服务注册和服务发现
4. 做代理、过滤器、容错、负载均衡
5. 做网络传输

可以把它理解成：

`真正让 RPC 跑起来的发动机`

### 3.2 `rpc-spring`

这个模块负责让 Spring 认识 RPC。

比如：
1. 看到 `@RpcReference` 时，注入代理对象
2. 看到 `@RpcService` 时，把服务发布出去

### 3.3 `rpc-spring-boot-starter`

这个模块负责让 Spring Boot 更省事。

也就是：
1. 自动创建必要的 Bean
2. 自动做扫描和装配
3. 使用者少写配置

## 4. 哪些是示例项目

### 4.1 `example-api`

放公共接口。

比如：
1. `HelloService`
2. `User`

它的作用是让 provider 和 consumer 共享同一份服务契约。

### 4.2 `example-provider`

放服务实现。

比如：
1. `HelloServiceImpl`
2. 启动 provider 应用

### 4.3 `example-consumer`

放调用方示例。

比如：
1. 注入 `HelloService`
2. 调用 `sayHello`
3. 打印结果

## 5. 为什么要拆成这些模块

因为一个完整的 RPC 项目，天然就有几种不同角色：
1. 公共接口
2. 提供服务的一方
3. 调用服务的一方
4. 框架本体
5. 框架和应用框架之间的集成层

如果全堆在一个模块里，后面会非常难看。

## 6. `rpc-core` 再往下怎么看

你不用第一眼就把 `rpc-core` 全部包名记住。

第一遍只记 5 层就够了：

```text
api           对外入口
config        配置
invoke        调用编排
registry      注册发现
protocol+transport  协议与网络
```

再补一层“横切能力”：

```text
resilience / observability / extension
```

## 7. 最后用一句话记结构

`example-*` 是演示怎么用，`rpc-*` 是框架怎么实现。

这句话先记住，后面看代码时就不容易迷路。
