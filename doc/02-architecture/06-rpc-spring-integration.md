# RPC Spring 与 Spring Boot 接入详解

## 1. 文档目的

这份文档解释当前项目是怎么和 Spring（应用框架）、Spring Boot（应用启动框架）集成的，以及为什么这一步对框架使用体验非常重要。

重点包括：

1. 为什么要做 Spring 接入
2. `rpc-spring` 和 `rpc-spring-boot-starter` 分别解决什么问题
3. `@RpcService` 和 `@RpcReference` 如何生效
4. Spring Boot 下 `rpc.*` 配置如何接起来
5. 当前 Spring 接入的边界在哪里

建议配合这些类一起阅读：

1. `RpcSpringRegistrar`
2. `RpcSpringManager`
3. `EnableRpc`
4. `RpcSpringBootAutoConfiguration`
5. `RpcSpringBootProperties`
6. `RpcBootFrameworkProperties`
7. `RpcConsumerBootstrap`
8. `RpcProviderBootstrap`

---

## 2. 为什么要做 Spring 接入

如果一个 RPC 框架只能靠手写代码去：

1. 读取配置
2. 创建 bootstrap（启动器）
3. 注入引用
4. 发布服务

那它虽然能跑，但使用体验会很差。

在真实 Java 项目里，绝大多数业务代码最终都会放进 Spring 容器。  
如果 RPC 框架不接 Spring，就会出现：

1. 生命周期分裂
2. 引用注入不自然
3. 服务发布不自然
4. 配置体系割裂

所以 Spring 接入不是“附加功能”，而是接入体验的重要组成部分。

---

## 3. 当前 Spring 接入分成哪两层

当前不是一步直接做到 starter（启动器依赖包），而是分成两层。

### 3.1 `rpc-spring`

这是 Spring 层接入，主要解决：

1. 如何把 `@RpcService` 扫描成 Spring Bean
2. 如何给 Bean 上的 `@RpcReference` 字段注入代理
3. 如何在 Spring 生命周期里启动 provider bootstrap

### 3.2 `rpc-spring-boot-starter`

这是 Spring Boot 层接入，主要解决：

1. 自动装配（auto configuration，自动装配）
2. `rpc.*` 配置绑定
3. 按配置自动扫描服务
4. 尽量不需要显式写 `@EnableRpc`

也就是说：

1. `rpc-spring` 解决“能接上 Spring”
2. `rpc-spring-boot-starter` 解决“接起来要足够自然”

---

## 4. 注解层：@RpcService 与 @RpcReference

### 4.1 `@RpcService` 的意义

`@RpcService` 标记一个类是：

- 需要被发布成 RPC 服务的 provider（服务提供端）Bean

它本身不直接做发布动作，而是提供一个明确标记，让：

1. 扫描器找到它
2. bootstrap 决定如何发布它

### 4.2 `@RpcReference` 的意义

`@RpcReference` 标记一个字段是：

- 需要注入 RPC 代理对象的 consumer（消费端）引用

这样业务代码就不必手动写：

```java
RpcConsumerBootstrap.fromConfig().getService(...)
```

而是像普通依赖注入一样声明即可。

### 4.3 显式接口与隐式接口

当前这两个注解都支持：

1. 显式指定接口类型
2. 不指定时按字段类型或默认契约推断

这样既能简化常见场景，也能在多接口场景里保留显式控制能力。

---

## 5. rpc-spring：Spring 层如何接入

### 5.1 RpcSpringRegistrar 做什么

`RpcSpringRegistrar` 的职责是：

1. 注册 `RpcSpringManager`
2. 按包扫描 `@RpcService`
3. 把这些类注册成 Spring `BeanDefinition（Bean 定义）`

它做的是“把类放进 Spring 容器”，而不是“真正发布 RPC 服务”。

### 5.2 为什么 Registrar 只注册 BeanDefinition

因为在 Spring 体系里：

1. `BeanDefinition` 注册阶段只是告诉容器“有这个 Bean”
2. 真正的实例创建、依赖注入和生命周期回调都在后面

如果在这个阶段就直接做 RPC 发布，时机会太早。

### 5.3 EnableRpc 的作用

在纯 Spring 场景下，通过：

`@EnableRpc`

来触发这套注册逻辑。

所以它本质上是 Spring 场景下的启用开关。

---

## 6. RpcSpringManager：Spring 集成的核心桥梁

### 6.1 为什么它是最核心的类

`RpcSpringManager` 同时实现了多个 Spring 扩展接口，例如：

1. `BeanPostProcessor`
2. `SmartLifecycle`
3. `ApplicationContextAware`
4. `DisposableBean`

这意味着它可以同时管理：

1. Bean 初始化前的引用注入
2. 容器启动时的服务发布
3. 容器关闭时的资源释放

它本质上就是：

- Spring 容器和 RPC 框架之间的桥梁

### 6.2 BeanPostProcessor 阶段做什么

在 `postProcessBeforeInitialization` 阶段，它会扫描 Bean 字段上的：

`@RpcReference`

并注入代理对象。

也就是说，consumer 引用注入发生在 Spring Bean 初始化之前。

### 6.3 SmartLifecycle 阶段做什么

当容器进入启动阶段时，`start()` 会：

1. 找到所有 `@RpcService` Bean
2. 获取 provider bootstrap
3. 把这些 Bean 注册成 RPC 服务
4. 启动 provider server

这意味着服务发布发生在 Spring 容器已经把 Bean 创建完成之后。

### 6.4 destroy 阶段做什么

在容器销毁时，它会负责关闭内部创建的：

1. provider bootstrap
2. consumer bootstrap

从而让 Spring 生命周期和 RPC 生命周期保持一致。

---

## 7. 为什么要区分“外部已有 bootstrap”和“内部创建 bootstrap”

`RpcSpringManager` 不是一上来就自己 `new` bootstrap，而是：

1. 优先复用容器中已有的 bootstrap
2. 没有时才内部创建

这样做的原因是：

1. 保留外部显式控制能力
2. 避免 Spring 集成偷偷绕过业务方配置
3. 同时兼容高级用法和默认用法

这是当前接入设计比较稳妥的一点。

---

## 8. 为什么 Spring 场景要关闭 core 的重复扫描

在 Spring 场景下，`RpcSpringManager` 已经把：

1. `@RpcService` 扫描
2. Bean 创建
3. Bean 生命周期

都交给了 Spring 容器。

如果这时 core 层的 provider bootstrap 再按配置扫描一遍，就会有风险：

1. 重复创建服务对象
2. 重复发布服务
3. 生命周期不一致

所以 Spring 场景下会关闭 core 层重复扫描。

---

## 9. 为什么还要做 rpc-spring-boot-starter

如果只有 `rpc-spring`，Spring Boot 项目里往往还需要：

1. 手动加 `@EnableRpc`
2. 手动准备扫描包
3. 手动把配置和 bootstrap 对上

这虽然能用，但还不够“Boot 风格”。

所以又做了 `rpc-spring-boot-starter`。

---

## 10. RpcSpringBootAutoConfiguration 做什么

### 10.1 自动装配的目标

它的目标是：

只要业务项目引入 starter、写好 `rpc.*` 配置，就能自动接起 RPC 框架。

### 10.2 它主要做三件事

1. 注册 `RpcFrameworkConfig`
2. 注册 `RpcSpringManager`
3. 注册 provider 侧的服务扫描逻辑

也就是说，starter 把原本需要手写的 Spring 接入动作自动化了。

### 10.3 为什么它还要自己做一次 @RpcService 扫描

因为在 Boot 场景下，不强制要求显式写：

`@EnableRpc`

所以 starter 需要自己补上这层能力。

---

## 11. RpcBootFrameworkProperties：配置适配层

### 11.1 为什么 starter 里还要单独配置对象

因为 Spring Boot 下的配置结构通常是：

```yaml
rpc:
  ...
```

而 core 层最终需要的统一对象是：

`RpcFrameworkConfig`

为了不让 core 直接依赖 Spring Boot 配置 API（应用程序接口），starter 引入了：

`RpcBootFrameworkProperties`

### 11.2 它负责什么

它负责：

1. 把 `rpc.*` 绑定成 Boot 风格嵌套对象
2. 再显式转换成 `RpcFrameworkConfig`

这一步很重要，因为它把：

1. Boot 配置结构
2. core 统一配置模型

明确分离开了。

### 11.3 为什么方法级配置也要在这里转换

因为 Boot 下的方法级配置通常是嵌套结构，而 core 最终需要的是：

`MethodConfig`

所以这里会完成一次：

`ClientMethod（客户端方法配置） -> MethodConfig（方法配置）`

的转换。

---

## 12. 当前 Spring 接入的收益

当前这套 Spring 接入带来了几个明显好处：

1. 服务发布和引用注入更加自然
2. 生命周期统一到 Spring 容器里
3. Boot 配置可直接驱动框架
4. 外部接入代码明显减少

---

## 13. 当前边界

当前 Spring 接入已经形成骨架，但仍有边界：

1. 还不是完整生态级 starter
2. 还没有更深的条件化配置拆分
3. 还没有非常细粒度的注解级方法治理配置

但从“先把骨架搭起来”的目标看，当前已经足够清晰。

---

## 14. 小结

当前项目已经把 Spring 集成拆成了两层：

1. `rpc-spring` 负责把 RPC 框架接进 Spring 容器生命周期
2. `rpc-spring-boot-starter` 负责把这套接入进一步自动化、配置化

这样既保留了 core 的独立性，又让真实项目接入方式更接近主流框架。
