# Spring 集成小白版

## 1. 为什么这个项目还要单独做 Spring 集成

因为很多业务项目本来就是 Spring 或 Spring Boot 应用。

如果 RPC 不能自然接进 Spring 生命周期，用起来会很别扭。

## 2. Spring 集成主要帮你省了什么事

它主要帮你省了两类手工操作：
1. 手工创建 consumer 代理
2. 手工发布 provider 服务

## 3. 你在代码里能直接看到的体验

### consumer 侧

```java
@RpcReference
private HelloService helloService;
```

### provider 侧

```java
@RpcService(HelloService.class)
public class HelloServiceImpl implements HelloService {
}
```

这就是 Spring 集成最直观的价值。

## 4. 核心角色是谁

第一遍先记住：
1. `RpcSpringManager`
2. `RpcSpringRegistrar`
3. `EnableRpc`
4. `RpcSpringBootAutoConfiguration`

## 5. `RpcSpringManager` 到底做什么

它主要干两件事：
1. 给 `@RpcReference` 字段注入代理
2. 在容器启动时把 `@RpcService` Bean 发布到 RPC 框架

## 6. 为什么 Boot Starter 还要单独一层

因为 Spring 集成和 Spring Boot 自动装配不是一回事。

可以理解成：
1. `rpc-spring` 负责“能接入 Spring”
2. `rpc-spring-boot-starter` 负责“接入得更省事”

## 7. 用一句话记住 Spring 集成

`Spring 集成的作用，是让 RPC 不再需要手工拼装，而是自然融入 Spring 应用。`
