# 新手阅读路线图

## 1. 先别急着看源码

如果你是第一次看这个项目，先记住一句话：

`它是一个自己实现的 RPC 框架，并且额外做了 Spring / Spring Boot 集成。`

这句话已经概括了项目的主体。

你现在最应该做的不是死扣每个类，而是先搞清楚三个问题：
1. 什么是 RPC
2. 这个项目里谁是 provider，谁是 consumer
3. 一次调用是怎么从 consumer 跑到 provider 的

## 2. 什么是 RPC

RPC 可以先用最简单的话理解：

`像调用本地方法一样，去调用另一台机器上的方法。`

比如你在 consumer 里写：

```java
helloService.sayHello("consumer")
```

你看到的是本地方法调用。

但真实发生的是：
1. consumer 把这次调用包装成请求
2. 请求通过网络发给 provider
3. provider 找到真正的 `HelloServiceImpl`
4. 执行完以后把结果再返回

## 3. 当前项目最重要的 6 个模块

```text
rpc-core                  框架核心
rpc-spring                Spring 集成
rpc-spring-boot-starter   Spring Boot 自动装配
example-api               公共接口
example-provider          服务提供者示例
example-consumer          服务消费者示例
```

先只记这个，不要一开始就背包名。

## 4. 建议阅读顺序

### 第一步：先看整体图

1. [05-project-visual-guide.md](./05-project-visual-guide.md)
2. [02-project-structure-guide.md](./02-project-structure-guide.md)

### 第二步：再看怎么跑起来

1. [03-current-usage-guide.md](./03-current-usage-guide.md)

### 第三步：再看一次真实调用

1. [../02-architecture/09-helloService-call-trace.md](../02-architecture/09-helloService-call-trace.md)

## 5. 第一遍读源码时要抓什么

只抓下面这些概念：
1. `@RpcReference` 是消费者注入代理
2. `@RpcService` 是提供者暴露服务
3. `RpcConsumerBootstrap` 是 consumer 入口
4. `RpcProviderBootstrap` 是 provider 入口
5. `RpcSpringManager` 是 Spring 集成的中枢

## 6. 第一遍不要急着深挖什么

先不要急着深挖：
1. 协议头细节
2. Netty pipeline 细节
3. SPI 扩展实现细节
4. 熔断限流算法细节

这些都属于第二遍、第三遍再看。

## 7. 你读完第一遍后应该达到什么效果

至少要能自己说出这段话：

`这个项目是一个自研 RPC 框架。consumer 通过代理发起调用，provider 暴露服务，注册中心负责服务注册和发现，Netty 负责网络通信，Spring 集成负责把这些东西接进应用生命周期。`

如果你已经能完整说出这句话，说明整体方向已经对了。
