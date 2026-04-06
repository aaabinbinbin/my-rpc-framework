# 我对这个 RPC 项目的理解笔记

这份文档用于持续记录我对项目的理解。

记录方式：

1. 先通过阅读源码和对话确认某个模块的真实行为。
2. 再把已经确认过的内容整理进这份笔记。
3. 后续随着理解加深，持续修正、补充和重构这里的内容。

当前阶段先整理 Spring 集成层里已经确认清楚的部分：

- `@RpcReference` 是如何注入的
- `@RpcService` 是如何变成 Spring Bean 并被发布的
- `RpcSpringManager` 的职责
- `RpcSpringManager.start()` 的真实发布流程

---

# 一、Spring集成层

## 1. 目前对 Spring 集成层的总体理解

这个项目里 Spring 集成层的核心目标不是实现 RPC 本身，而是把 `rpc-core` 的能力接到 Spring 生命周期上。

可以先把相关模块理解成三层：

- `rpc-core`：RPC 核心能力，包含代理、调用链、注册发现、协议、传输、服务执行
- `rpc-spring`：把 RPC 接入 Spring 容器生命周期
- `rpc-spring-boot-starter`：进一步通过自动配置接入 Spring Boot

在 Spring 场景下，最核心的总控类是：

- `rpc-spring/src/main/java/com/rpc/spring/RpcSpringManager.java`

它主要做两件事：

1. 在普通 Spring Bean 初始化前，把字段上的 `@RpcReference` 注入成 RPC 代理对象。
2. 在 Spring 容器启动完成后，把容器里的 `@RpcService` Bean 发布成真正可远程调用的 RPC 服务。

---

## 2. `@RpcReference` 和 `@RpcService` 要分成两条线理解

这两个注解虽然都和 RPC 有关，但它们在 Spring 里的处理路径完全不同。

### 2.1 `@RpcService`

`@RpcService` 标记的是类。

它的处理路径是：

1. 先被扫描到。
2. 先注册成 Spring `BeanDefinition`。
3. 再由 Spring 正常实例化成 Bean。
4. 最后由 `RpcSpringManager.start()` 把这个已经存在的 Spring Bean 发布成 RPC 服务。

所以，`@RpcService` 对应的服务实现类，本质上仍然是 Spring Bean。

### 2.2 `@RpcReference`

`@RpcReference` 标记的是字段，不是类。

它的处理路径是：

1. 字段所在的类本身先由 Spring 走正常的 Bean 创建流程。
2. 在 Bean 初始化前，`RpcSpringManager` 扫描字段。
3. 发现字段上有 `@RpcReference` 时，使用 `RpcConsumerBootstrap` 创建代理对象。
4. 再用反射把这个代理对象写回字段。

所以，`@RpcReference` 对应的不是“把某个类实例化成 Bean”，而是“把某个字段替换成 RPC 代理对象”。

---

## 3. `RpcSpringRegistrar` 的作用

关键类：

- `rpc-spring/src/main/java/com/rpc/spring/RpcSpringRegistrar.java`

`RpcSpringRegistrar` 实现的是 `ImportBeanDefinitionRegistrar`。

它的核心职责不是创建对象，而是向 Spring 容器注册 `BeanDefinition`。

它做了两件事：

1. 如果容器里还没有 `RpcSpringManager`，先把 `RpcSpringManager` 注册成一个 Spring `BeanDefinition`。
2. 扫描指定包下带 `@RpcService` 的类，并把这些类注册成 Spring `BeanDefinition`。

这里要特别注意：

- `RpcSpringRegistrar` 注册的是 `BeanDefinition`，不是直接 new 出来的对象。
- 后续这些类还是会由 Spring 自己完成实例化、依赖注入和初始化。

### 3.1 什么时候会用到 `RpcSpringRegistrar`

当项目使用 `rpc-spring` 并显式写了 `@EnableRpc` 时，会通过 `@Import` 的方式触发 `RpcSpringRegistrar`。

也就是说：

1. Spring 解析配置类。
2. 遇到 `@EnableRpc`。
3. 进而触发 `RpcSpringRegistrar.registerBeanDefinitions(...)`。

### 3.2 在 Spring Boot 场景下

如果用的是 `rpc-spring-boot-starter`，则更常通过自动配置完成类似工作，而不一定依赖显式的 `@EnableRpc`。

对应类：

- `rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcSpringBootAutoConfiguration.java`

这个类中使用 `BeanDefinitionRegistryPostProcessor` 来扫描 `@RpcService` 并注册对应的 `BeanDefinition`。

---

## 4. `RpcSpringManager` 为什么实现这么多 Spring 接口

关键类：

- `rpc-spring/src/main/java/com/rpc/spring/RpcSpringManager.java`

类声明如下：

```java
public class RpcSpringManager implements BeanPostProcessor, SmartLifecycle,
        DisposableBean, ApplicationContextAware, PriorityOrdered
```

它实现多个接口，是因为它需要卡住 Spring 生命周期的多个阶段。

### 4.1 `BeanPostProcessor`

作用：

- 在 Spring Bean 初始化前后插手 Bean 处理流程。

`RpcSpringManager` 里主要用它的：

- `postProcessBeforeInitialization(...)`

这个方法负责：

- 扫描当前 Bean 的字段
- 找出 `@RpcReference`
- 注入 RPC 代理对象

也就是说，`@RpcReference` 的注入发生在 Bean 初始化之前。

### 4.2 `SmartLifecycle`

作用：

- 参与 Spring 容器的启动与停止过程。

`RpcSpringManager` 用它来在容器启动完成后发布 `@RpcService`。

关键方法：

- `start()`：发布服务并启动 provider
- `stop()`：停止时复用销毁逻辑
- `isRunning()`：标记当前是否已经启动
- `isAutoStartup()`：返回 `true`，表示跟随容器自动启动
- `getPhase()`：返回 `Integer.MAX_VALUE`，表示尽量晚启动，等普通 Bean 都稳定后再启动 provider

### 4.3 `DisposableBean`

作用：

- Spring 容器销毁时执行 `destroy()`

`RpcSpringManager.destroy()` 负责关闭内部创建的 bootstrap 和清理运行状态。

### 4.4 `ApplicationContextAware`

作用：

- 让当前类拿到 `ApplicationContext`

有了容器引用之后，`RpcSpringManager` 才能：

- 查找所有 `@RpcService` Bean
- 获取已有的 `RpcProviderBootstrap`
- 获取已有的 `RpcConsumerBootstrap`
- 获取 `RpcFrameworkConfig`

### 4.5 `PriorityOrdered`

作用：

- 控制当前 BeanPostProcessor 的执行优先级

`RpcSpringManager.getOrder()` 返回最高优先级，目的是尽量早地完成 `@RpcReference` 注入。

---

## 5. `@RpcReference` 的注入过程

关键方法：

- `RpcSpringManager.postProcessBeforeInitialization(...)`
- `RpcSpringManager.injectReference(...)`

逻辑可以概括为：

1. Spring 正在初始化某个 Bean。
2. `RpcSpringManager` 作为 `BeanPostProcessor` 被调用。
3. 它扫描该 Bean 的字段。
4. 如果字段上有 `@RpcReference`，就确定服务类型。
5. 再通过 `RpcConsumerBootstrap.getService(serviceType)` 获取代理对象。
6. 最后使用反射把代理对象写回该字段。

这一步注入进去的是：

- RPC 代理对象

而不是：

- provider 的真实实现类对象
- Spring 容器里已有的某个业务 Bean

所以 `@RpcReference` 的本质是“字段代理注入”。

---

## 6. `@RpcService` 是如何变成可发布服务的

`@RpcService` 对应的类并不是由 RPC 框架在 Spring 外部自行 new 出来的。

准确流程是：

1. `RpcSpringRegistrar` 或 Spring Boot 自动配置先把它注册成 `BeanDefinition`。
2. Spring 再按普通 Bean 的方式实例化这个服务实现类。
3. 容器启动完成后，`RpcSpringManager.start()` 再从容器里取出这些真实 Bean。
4. 然后把它们发布为 RPC 服务。

所以一定要区分三件事：

1. 注册成 `BeanDefinition`
2. 实例化成 Spring Bean
3. 发布成 RPC 服务

`@RpcService` 会完整经历这三步。

---

## 7. `RpcSpringManager.start()` 的发布流程

关键方法：

- `RpcSpringManager.start()`

我目前对这个方法的理解如下。

### 7.1 防止重复启动

```java
if (running) {
    return;
}
```

作用：

- 如果当前已经启动过，就直接返回，避免重复发布服务和重复启动 provider。

### 7.2 从容器中找出所有 `@RpcService` Bean

```java
String[] serviceBeanNames = applicationContext.getBeanNamesForAnnotation(RpcService.class);
```

作用：

- 直接从 Spring 容器里找出所有被 `@RpcService` 标记的 Bean 名称。

这说明此时已经不是类路径扫描阶段，而是“从容器里拿现成 Bean”的阶段。

### 7.3 只有存在服务时才启动 provider 发布逻辑

```java
if (serviceBeanNames.length > 0) {
```

作用：

- 如果当前应用里根本没有 `@RpcService`，就不需要启动 provider。

### 7.4 获取 provider 侧 bootstrap

```java
RpcProviderBootstrap bootstrap = getProviderBootstrap();
```

作用：

- 获取 provider 侧总装配器。

它后续负责：

1. 注册服务到本地注册表
2. 启动 RPC 服务端

### 7.5 逐个处理每个 `@RpcService` Bean

```java
for (String beanName : serviceBeanNames) {
```

作用：

- 当前应用里可能存在多个服务实现类，需要逐个发布。

### 7.6 从 Spring 容器里拿到真实服务 Bean

```java
Object bean = applicationContext.getBean(beanName);
```

作用：

- 从容器中获取真实服务对象。

这一步拿到的是：

- Spring 容器里的真实 Bean

不是：

- `BeanDefinition`
- RPC 代理对象
- RPC 框架自己 new 出来的对象

### 7.7 读取该 Bean 上的 `@RpcService` 注解

```java
RpcService rpcService = bean.getClass().getAnnotation(RpcService.class);
```

作用：

- 读取注解上的配置，尤其是显式指定的服务接口类型。

### 7.8 解析服务接口并注册服务

```java
bootstrap.registerService(resolveServiceInterface(bean.getClass(), rpcService), bean);
```

这一步包含两个动作。

第一个动作：`resolveServiceInterface(...)`

- 如果 `@RpcService` 显式声明了接口，就用注解里的接口。
- 如果没声明，则要求实现类只能实现一个接口。
- 如果实现多个接口却未显式指定，则抛异常。

第二个动作：`bootstrap.registerService(...)`

- 把“服务接口名 -> 真实服务 Bean”注册到 provider 本地注册表。
- 如果配置了注册中心，也会同步注册服务地址到注册中心。

这里要特别强调：

- 注册进本地注册表的是“真实 Spring 服务 Bean”
- 不是 consumer 代理对象

### 7.9 启动 provider 服务器

```java
bootstrap.start();
```

作用：

- 启动 provider 侧底层 RPC 服务端。

在 Spring 场景下，`getProviderBootstrap()` 会显式关闭 core 层重复的注解扫描，避免同一服务被重复注册。

也就是说，这里的重点是：

- 启动网络服务端
- 开始监听 RPC 请求

### 7.10 标记当前已启动

```java
running = true;
```

作用：

- 表示当前 Spring 集成层已经完成 provider 启动。

---

## 8. `getProviderBootstrap()` 的一个关键设计

`getProviderBootstrap()` 会强制设置：

```java
frameworkConfig.setServerAutoRegisterAnnotatedServices(false);
```

我的理解是：

- 在 Spring 场景里，`@RpcService` 的扫描、实例化和对象管理已经由 Spring 接管。
- 因此 core 层不能再自己去扫描并实例化这些注解类。
- 否则会出现同一个服务被重复注册，甚至 Spring Bean 和 core 自己 new 出来的实例不一致的问题。

所以这个设计的本质是：

- Spring 管理对象
- RPC 复用 Spring Bean 做服务发布
- 避免重复扫描和重复实例化

---

## 9. Spring 销毁阶段的处理

`RpcSpringManager.destroy()` 会在容器销毁时关闭 bootstrap。

但这里不是无条件关闭，而是：

- 只关闭 `RpcSpringManager` 自己内部创建的 bootstrap

对应标记为：

- `internalProviderBootstrap`
- `internalConsumerBootstrap`

如果 bootstrap 是外部已经作为 Spring Bean 提供进来的，则 `RpcSpringManager` 不会擅自关闭。

所以它考虑了资源归属问题。

---

## 10. 当前我已经确认的几个关键结论

### 10.1 关于 `@RpcService`

- `@RpcService` 对应的实现类最终是 Spring Bean。
- 这些 Bean 是先被 Spring 创建出来，再被发布成 RPC 服务。
- 发布时进入本地注册表的是“真实服务 Bean”。

### 10.2 关于 `@RpcReference`

- `@RpcReference` 标记的是字段，不是类。
- 它不是把服务实现类注入进来，而是注入远程调用代理对象。
- 注入时机是 Bean 初始化前。

### 10.3 关于 `RpcSpringManager`

- 它是 Spring 集成层的总控和接线层。
- 不是 RPC 核心实现本身。
- 它的价值在于卡住了 Spring 生命周期中的正确时机。

### 10.4 关于 `RpcSpringManager.start()`

- 先找出所有 `@RpcService` Bean
- 再逐个从容器里拿到真实 Bean
- 解析服务接口
- 把“接口名 -> 真实服务 Bean”注册到本地注册表
- 同步注册到注册中心
- 最后启动 provider 网络服务端

---

## 11. 后续准备继续补充的内容

后面我准备继续沿着下面的顺序补充这份笔记：

1. `resolveServiceInterface()` 为什么这样设计
2. `RpcProviderBootstrap.registerService()` 和 `LocalRegistryImpl.register()` 的细节
3. ZooKeeper 里到底注册了什么节点
4. `RpcConsumerBootstrap` 是如何创建代理的
5. `RpcProxyFactory` / `RpcInvocationHandler` 如何把本地调用变成 `RpcRequest`
6. consumer 过滤链、invoker 过滤链、provider 过滤链之间的区别

---

## 12. `postProcessBeforeInitialization()` 在 Spring Bean 生命周期中的位置

我目前对 `RpcSpringManager.postProcessBeforeInitialization()` 的理解是：

- 它插在“Bean 已经实例化、依赖注入基本完成、初始化逻辑尚未执行”这个位置。

可以先把一个普通 Spring Bean 的创建主线简化理解为：

1. Spring 根据 `BeanDefinition` 实例化对象
2. Spring 进行依赖注入
3. Spring 回调一些 `Aware` 接口
4. 执行 `BeanPostProcessor.postProcessBeforeInitialization(...)`
5. 执行初始化逻辑
6. 执行 `BeanPostProcessor.postProcessAfterInitialization(...)`
7. Bean 创建完成，进入单例池

所以，`RpcSpringManager.postProcessBeforeInitialization()` 所在的位置是第 4 步。

### 12.1 在这个项目里的具体意义

这意味着：

1. Bean 对象此时已经创建出来了，所以可以操作字段。
2. Bean 的初始化逻辑还没执行，所以还来得及把 `@RpcReference` 字段替换成代理对象。
3. 后续如果 Bean 有自己的初始化逻辑，例如 `@PostConstruct`，通常拿到的就已经是注入好的 RPC 代理。

所以 `@RpcReference` 的注入发生在：

- Bean 创建阶段内部
- 但晚于实例化
- 早于初始化方法执行

### 12.2 和 `@RpcService` 发布不是同一个阶段

这里还要再次区分两条线：

- `@RpcReference` 注入：发生在 Bean 创建阶段，通过 `postProcessBeforeInitialization()`
- `@RpcService` 发布：发生在容器启动完成后，通过 `RpcSpringManager.start()`

所以不能把这两件事看成同一个时机。

---

## 13. 目前关于 Spring 集成层还需要额外记住的两个细节

### 13.1 `RpcSpringManager.start()` 是“尽量晚启动”的

`RpcSpringManager` 作为 `SmartLifecycle`，其 `getPhase()` 返回的是：

```java
Integer.MAX_VALUE
```

我的理解是：

- 它希望等大部分普通 Bean 都准备完成之后，再去发布 provider 服务并启动 RPC 服务端。
- 这样能避免容器还没稳定时就提前对外提供 RPC 能力。

### 13.2 Spring Boot 场景下，`@RpcService` 扫描更多依赖配置

在 `rpc-spring-boot-starter` 场景里，`@RpcService` 的扫描不是默认全项目扫描，而是依赖：

- `rpc.spring.scan-packages`

也就是说：

- 如果用了 starter，却没有正确配置扫描包，就可能导致 `@RpcService` 没有被注册成 `BeanDefinition`。

这一点和显式 `@EnableRpc` 的场景要分开理解。

---

# **二、服务端**

## 14. 服务端 Bootstrap 的总体职责

服务端 Bootstrap 对应的核心类是：

- `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcProviderBootstrap.java`

我目前的理解是：

- `RpcProviderBootstrap` 不是某次请求的执行器
- 它是 provider 启动期的总装配器

它主要负责三件事：

1. 组装 provider 运行环境
2. 注册服务
3. 启动服务端

也就是说，它负责把“一个本地服务对象”变成“一个真正可对外提供 RPC 调用的 provider”。

### 14.1 `RpcProviderBootstrap` 内部最关键的对象

它内部最重要的成员有：

- `ServiceRegistry serviceRegistry`
- `RpcServer rpcServer`
- `RpcFrameworkConfig frameworkConfig`

我的理解：

- `serviceRegistry`：负责把服务地址注册到注册中心
- `rpcServer`：负责真正监听端口、接收请求、执行分发
- `frameworkConfig`：provider 运行时总配置

### 14.2 `fromConfig(...)` 做了什么

`RpcProviderBootstrap.fromConfig(...)` 的主要作用不是启动 provider，而是先把 provider 运行所需的基础设施装配好。

它会依次做这些事：

1. 创建 provider 侧降级策略
2. 配置过滤器管理器
3. 配置 provider 过滤器运行时参数
4. 创建注册中心客户端 `ServiceRegistry`
5. 组装 `RpcServerConfig`
6. 创建真正的 `RpcServer`

所以我目前把 `fromConfig(...)` 理解为：

- “准备 provider 运行环境”

而不是：

- “真正开始提供服务”

### 14.3 `registerService(...)` 做了什么

核心代码是：

```java
rpcServer.getLocalRegistry().register(serviceInterface.getName(), serviceImpl);
```

这一步的本质是：

- 以服务接口全限定名作为服务名
- 把“服务名 -> 真实服务对象”注册到 provider 本地注册表

这里非常重要的一点是：

- 注册进去的是“真实服务对象”
- 不是代理对象

同时，这一步继续往下还会触发注册中心地址注册。

### 14.4 `start()` 做了什么

`RpcProviderBootstrap.start()` 在 Spring 场景下主要做的事情就是：

- 启动底层 `rpcServer`

因为 Spring 场景下已经显式关闭了 core 层自动注解扫描注册，所以这里的重点不再是扫描服务，而是：

- 启动 Netty Server
- 绑定端口
- 对外开始监听 RPC 请求

---

## 15. 服务注册进入 `LocalRegistryImpl.register(...)` 后发生了什么

服务端发布链继续往下会进入：

- `rpc-core/src/main/java/com/rpc/core/registry/impl/LocalRegistryImpl.java`

这里的 `register(String serviceName, Object serviceInstance)` 是 provider 服务注册的核心实现之一。

### 15.1 参数校验

首先会检查：

- `serviceName` 不能为空
- `serviceInstance` 不能为空

我的理解是：

- provider 一旦注册阶段这两个信息有问题，后面请求到了本地分发时必然会失败
- 所以这里属于尽早失败

### 15.2 先注册到外部注册中心

如果存在 `serviceRegistry`，就会执行：

- `serviceRegistry.register(serviceName, new InetSocketAddress(host, port))`

这一步注册出去的是：

- 服务名
- 当前 provider 的网络地址

所以注册中心里保存的不是服务对象，而是：

- `serviceName -> host:port`

这份数据是给 consumer 做服务发现用的。

### 15.3 再写入本地注册表

接着会执行：

```java
SERVICE_MAP.put(serviceName, serviceInstance);
```

这一步写入的是：

- `serviceName -> 真实服务对象`

这张表是 provider 自己内部真正用来做本地调用分发的依据。

后面 provider 收到请求后，就是靠 `serviceName` 从这里拿到本地真实对象，再继续反射执行方法。

### 15.4 当前本地注册表是 JVM 级静态共享的

`SERVICE_MAP` 是：

```java
private static final Map<String, Object> SERVICE_MAP = new ConcurrentHashMap<>();
```

这意味着：

- 同一个 JVM 进程里，所有 `LocalRegistryImpl` 实例共享这一份本地服务表

这是当前实现里很重要的一个细节。

### 15.5 同时会初始化指标对象

服务注册时还会顺手调用：

- `ServiceMetricsManager.getInstance().register(serviceName)`

所以服务一旦注册，不仅能被调用，也会准备好对应的 metrics 统计入口。

### 15.6 这里其实发生了两种“注册”

我目前已经明确区分这两种注册：

第一种是本地注册：

- `serviceName -> serviceInstance`
- 用于 provider 本地执行时找到真实对象

第二种是注册中心注册：

- `serviceName -> host:port`
- 用于 consumer 做服务发现

这两者一定不能混淆。

---

## 16. `RpcNettyServer` 启动时做了什么

服务端真正启动时，核心类是：

- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/RpcNettyServer.java`

我目前把它理解成：

- provider 侧真正的网络服务端实现

### 16.1 构造时先准备哪些东西

在构造阶段，它已经提前准备好了：

1. `LocalRegistry`
2. `ServerLifecycle`
3. `bizExecutor`
4. `requestProcessor`

我的理解如下：

- `LocalRegistry`：本地服务表，后面按服务名找到真实对象
- `ServerLifecycle`：记录当前是否接收新请求、当前有多少请求正在执行
- `bizExecutor`：业务线程池，专门执行真实业务方法
- `requestProcessor`：服务端请求处理入口，内部是 `RpcRequestDispatcher + RpcRequestExecutor`

也就是说，在真正 `start()` 之前，provider 本地执行链的关键依赖已经装配好了。

### 16.2 `start()` 的主要动作

`RpcNettyServer.start()` 主要做 4 件事：

1. 创建 Netty 的 boss / worker 线程组
2. 配置 `ServerBootstrap`
3. 配置每条连接的 pipeline
4. 绑定端口开始监听

### 16.3 provider 侧 pipeline 当前顺序

当前 provider pipeline 顺序是：

1. `IdleStateHandler`
2. `ServerHeartbeatHandler`
3. `RpcProtocolDecoder`
4. `RpcProtocolEncoder`
5. `RpcRequestHandler`

我当前的理解是：

- 前两层更偏连接和空闲状态管理
- 中间两层负责协议编解码
- 最后一层才真正进入 provider 请求处理入口

### 16.4 请求到来后的第一站

请求经过解码后会进入：

- `RpcRequestHandler`

它会把请求交给：

- `requestProcessor.process(...)`

而当前 `requestProcessor` 实际上就是：

- `RpcRequestDispatcher`

所以 provider 收到请求后的第一层业务分发入口是：

- `RpcRequestDispatcher`

---

## 17. `RpcRequestDispatcher` 的职责

关键类：

- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestDispatcher.java`

我的理解：

- 它是 provider 入口的第一层分流器

它首先根据协议头里的 `messageType` 判断当前请求是什么类型。

### 17.1 心跳请求

如果是心跳请求：

- 直接构造心跳响应返回

不会进入业务执行链。

### 17.2 业务请求

如果是业务请求：

- 会继续交给 `RpcRequestExecutor`

所以 provider 端不是收到包后直接反射调业务方法，而是先经过：

- 消息类型分流

---

## 18. `RpcRequestExecutor` 如何真正执行业务请求

关键类：

- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestExecutor.java`

这个类是 provider 端真正的业务执行器。

它主要负责：

1. 使用业务线程池执行请求
2. 恢复 provider 侧 `RpcContext`
3. 从本地注册表中找到真实服务对象
4. 构造 provider 过滤链上下文
5. 执行 provider 过滤链
6. 最终通过反射调用目标方法
7. 包装成 `RpcResponse`

### 18.1 `execute(...)` 做了什么

`execute(...)` 的逻辑可以概括为：

1. 先增加 inflight 请求计数
2. 把真正执行逻辑提交给 `bizExecutor`
3. 同步等待业务线程池结果
4. 如果执行异常，统一包装成失败响应
5. 无论成功失败都减少 inflight 计数

所以它本质上实现的是：

- 同步 RPC 外观
- 但内部把业务执行从 Netty IO 线程切换到业务线程池

### 18.2 `invoke(...)` 做了什么

真正业务执行主线在 `invoke(...)` 里。

我的理解顺序如下：

1. 根据请求里的 `requestId`、`traceId` 和 attachments 恢复 provider 当前线程的 `RpcContext`
2. 根据 `serviceName` 从本地注册表拿到真实服务对象
3. 构造 `FilterContext`
4. 执行 `PROVIDER` 阶段过滤链
5. 过滤链的终点再调用 `invokeTarget(...)`
6. 如果过滤链直接返回了 `RpcResponse`，则直接返回
7. 如果只是普通业务结果，则统一包装成 `RpcResponse.success(...)`
8. 最后清理当前线程的 `RpcContext`

### 18.3 `invokeTarget(...)` 做了什么

最终真正落到业务方法时，执行的是：

1. 根据 `methodName + parameterTypes` 找到目标方法
2. 使用 `parameters` 反射调用这个方法

所以 provider 最终的本地执行，本质上是：

- 通过反射调用真实服务对象的方法

---

## 19. 服务端业务线程池的作用

业务线程池相关类：

- `rpc-core/src/main/java/com/rpc/core/runtime/server/BizThreadPool.java`

它创建的是：

- `ThreadPoolExecutor`
- 队列类型是 `LinkedBlockingQueue`

所以它不是延迟队列，不具备延迟执行语义。

我目前的理解是：

- 它的核心作用是把真实业务执行从 Netty IO 线程中隔离出去

这样做的原因是：

- 如果业务代码阻塞、变慢、访问数据库或调用下游服务，就不应该拖住 Netty 的网络线程

所以线程分工是：

1. boss 线程：接收连接
2. worker 线程：处理网络 IO、收包、解码、回包
3. biz 线程池：执行服务实现类的真实业务方法

线程池参数含义：

- 核心线程数：常驻工作线程数
- 最大线程数：高峰期可扩容到的线程数
- 队列容量：线程忙不过来时，最多可积压的任务数

当前拒绝策略是：

- `AbortPolicy`

也就是说：

- 如果线程和队列都满了，会直接拒绝任务

---

## 20. 服务端降级策略的理解

provider 侧降级策略相关类：

- `rpc-core/src/main/java/com/rpc/core/resilience/degrade/DegradationPolicyFactory.java`
- `rpc-core/src/main/java/com/rpc/core/resilience/degrade/FailFastDegradation.java`
- `rpc-core/src/main/java/com/rpc/core/resilience/degrade/DefaultValueDegradation.java`

当前 provider 侧支持两种降级策略：

### 20.1 `failFast`

含义：

- 直接返回一个“服务已降级”的失败响应

### 20.2 `defaultValue`

含义：

- 按 `serviceName#methodName` 查找预设默认值
- 如果找到，就直接返回该默认值
- 否则返回降级失败响应

### 20.3 在当前项目里，provider 降级主要和限流绑定

这一点很重要。

当前 provider 降级不是所有 provider 异常都会自动触发，而主要体现在：

- provider 限流失败之后如何兜底响应

也就是说：

- 当前 provider 太忙
- 限流器认为不应该继续执行业务
- 那么可以选择：
  - 直接失败
  - 或返回默认值

所以我当前对 provider 降级的理解是：

- 它是一种 provider 过载保护下的响应兜底机制

---

## 21. 服务端限流的实现

provider 限流相关类：

- `rpc-core/src/main/java/com/rpc/core/invoke/filter/impl/ProviderRateLimitFilter.java`
- `rpc-core/src/main/java/com/rpc/core/resilience/ratelimit/RateLimiterManager.java`
- `rpc-core/src/main/java/com/rpc/core/resilience/ratelimit/FixedWindowRateLimiter.java`

### 21.1 限流发生在 provider 过滤链的第一层

`ProviderRateLimitFilter` 会最先执行。

它会用：

- `serviceName#methodName`

作为限流 key。

所以当前 provider 限流粒度是：

- 方法级

### 21.2 `RateLimiterManager` 负责管理限流器，不直接实现算法

`RateLimiterManager` 的职责是：

1. 为每个 key 维护一个独立的限流器实例
2. 维护限流总开关和默认阈值
3. 请求到来时按 key 找到或创建对应限流器
4. 再调用该限流器的 `tryAcquire()`

所以：

- `RateLimiterManager` 负责“找谁来限”
- 具体“怎么限”由 `FixedWindowRateLimiter` 完成

### 21.3 当前使用的是固定窗口限流

`FixedWindowRateLimiter` 的算法是：

1. 按 1 秒切分时间窗口
2. 每个窗口内最多允许 `permitsPerSecond` 次请求通过
3. 每个 key 独立维护自己的窗口起点和当前窗口请求计数

所以它不是：

- 滑动窗口
- 令牌桶
- 漏桶

而是最基础的固定窗口算法。

---

## 22. 服务端 MDC 的理解

provider 侧 MDC 过滤器类：

- `rpc-core/src/main/java/com/rpc/core/invoke/filter/impl/ProviderMdcFilter.java`

我目前的理解是：

- MDC 是日志上下文
- 不是业务逻辑组件

provider 收到请求并恢复 `RpcContext` 后，`ProviderMdcFilter` 会把这些数据放进日志 MDC：

- `rpcRequestId`
- `rpcTraceId`
- `rpcService`
- `rpcMethod`

它的作用是：

- 让 provider 当前请求执行期间产生的日志自动带上链路信息

这样在日志系统中，就能把日志和某次 RPC 请求对应起来。

同时它会在链路结束后清理 MDC，避免线程复用导致串请求。

---

## 23. 服务端 Metrics 的理解

provider 侧 metrics 相关类：

- `rpc-core/src/main/java/com/rpc/core/invoke/filter/impl/ProviderMetricsFilter.java`
- `rpc-core/src/main/java/com/rpc/core/observability/metrics/ServiceMetricsManager.java`
- `rpc-core/src/main/java/com/rpc/core/observability/metrics/ServiceMetrics.java`
- `rpc-core/src/main/java/com/rpc/core/observability/metrics/ServerRuntimeMetrics.java`

### 23.1 ProviderMetricsFilter 不是只看当前请求

每次请求执行时，`ProviderMetricsFilter` 确实会记录本次请求的耗时和成功/失败结果。

但这些数据不会只停留在当前请求层面，而是会被累加到：

- 对应服务的 `ServiceMetrics`

当前聚合粒度是：

- 服务级

不是方法级。

### 23.2 当前服务级聚合指标包括

`ServiceMetrics` 目前统计的内容包括：

- 总调用次数
- 总失败次数
- 总耗时
- 最近一次调用耗时

并可以导出快照，得到：

- `totalCalls`
- `failedCalls`
- `averageLatencyNanos`
- `lastLatencyNanos`

所以我当前理解：

- 每次请求都会被记录
- 最终沉淀成服务维度的聚合统计

### 23.3 还有一类是运行时指标

除了服务调用 metrics，项目里还有：

- `ServerRuntimeMetrics`

它统计的是 provider 当前运行状态，例如：

- 是否还接收新请求
- inflight 请求数
- 业务线程池活跃线程数
- 当前线程池大小
- 队列大小

所以 provider 侧 metrics 其实分成两类：

1. 调用结果类指标
2. 运行状态类指标

---

## 24. 优雅停机时间的理解

服务端配置类：

- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/config/RpcServerConfig.java`

服务端停机相关类：

- `rpc-core/src/main/java/com/rpc/core/runtime/server/ServerLifecycle.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/RpcNettyServer.java`

当前配置里的：

- `shutdownTimeout`

我的理解是：

- 它不是单次请求超时
- 它是 provider 优雅停机时的最长等待窗口

provider 停机时会做这些事：

1. 停止接收新请求
2. 从注册中心摘除服务
3. 尽量等待正在执行的请求完成
4. 关闭监听端口
5. 关闭 Netty 线程组
6. 关闭业务线程池

其中等待 inflight 请求清空时，就会使用 `shutdownTimeout`。

所以它的本质是：

- 优雅停机时最多愿意等多久

目的是在下面两件事之间做平衡：

- 尽量让正在执行的请求跑完
- 又不能无限等待导致进程迟迟无法退出

---

## 25. 关于当前服务端限流实现的改进想法（先记录，暂不改代码）

这部分先作为后续优化思路记录，当前主线仍然以“先把项目理解透彻”为主。

### 25.1 当前服务端限流实现的基本特征

我目前已经明确的现状是：

1. 限流入口在 provider 过滤链的第一层
2. 限流粒度是方法级，key 为 `serviceName#methodName`
3. 当前算法是固定窗口
4. 时间窗口为 1 秒
5. 当前属于单机内存限流，不是分布式限流
6. 超限后会直接失败，或者走 provider 降级策略

所以当前服务端限流可以概括成：

- 单机
- 方法级
- 固定窗口
- 静态阈值

### 25.2 当前实现的局限

我当前认为主要有这些局限：

1. 固定窗口在窗口边界会有流量突刺问题
2. 只做单机限流，集群部署时整体流量上限会随实例数线性放大
3. 阈值是静态的，不会根据线程池、队列、inflight 等真实承压状态动态调节
4. 只有“放行 / 拒绝”两态，缺少更平滑的流量治理手段
5. 当前更多是 QPS 限流，没有把并发数控制单独拆开

### 25.3 最自然的第一步改进：保留接口，替换限流算法

当前项目已经有：

- `RateLimiter` 接口
- `RateLimiterManager`
- `ProviderRateLimitFilter`

所以最自然的第一步不是推翻结构，而是：

- 保持现有分层不变
- 把 `FixedWindowRateLimiter` 升级为可插拔的多种算法实现

例如未来可以考虑增加：

- 滑动窗口
- 令牌桶

这样改动面相对小，且收益很高。

### 25.4 第二步改进：让 `RateLimiterManager` 不再写死固定窗口

当前 `RateLimiterManager` 虽然管理的是 `RateLimiter` 接口，但内部实际上还是写死创建 `FixedWindowRateLimiter`。

所以一个自然的重构方向是：

- 引入限流器工厂或策略解析层
- 让不同服务 / 方法可以使用不同限流算法

这样就能从“只有一种算法”升级为“按场景选择算法”。

### 25.5 第三步改进：支持方法级差异化治理策略

未来如果要进一步增强 provider 限流能力，我认为可以让每个方法有独立的治理配置，例如：

- 是否限流
- 阈值是多少
- 使用哪种算法
- 超限后走哪种响应策略

这样可以做到：

- 热点方法更严格保护
- 核心方法和非核心方法使用不同策略

### 25.6 第四步改进：让限流和实例真实承压状态联动

当前项目已经有不少运行时状态信息，例如：

- inflight 请求数
- 业务线程池活跃线程数
- 业务线程池队列长度
- 服务端运行时指标

所以一个很自然的增强方向是：

- 不只看固定 QPS 是否超限
- 还结合当前 provider 是否已经快顶不住

例如未来可以考虑：

- 队列接近打满时主动收紧限流
- 线程池活跃度过高时提前降流
- inflight 请求过多时提前保护

这种思路的本质是：

- 从静态限流升级为运行时自适应保护

### 25.7 第五步改进：把并发控制单独拆出来

我当前认为：

- QPS 限流和并发数控制不是一回事

例如某个方法请求量不高，但单次执行特别慢，这时问题可能不是 QPS，而是同时执行的请求数过多。

所以未来如果继续增强 provider 保护能力，可以考虑新增类似：

- provider 并发限制过滤器

这样服务端保护就不只是一层 QPS 限流，而是：

- 流量限速
- 并发隔离
- 队列保护

### 25.8 第六步改进：丰富超限后的处理策略

当前超限后的处理方式比较基础：

- 直接失败
- 或走默认值降级

未来如果需要增强，可以考虑把“超限后如何响应”进一步抽象成独立策略层。

这样理论上可以支持：

- 返回默认值
- 返回缓存值
- 返回上次成功结果
- 对非核心请求快速失败，对核心请求保留容量

### 25.9 如果按当前项目结构给改进优先级

如果未来真的要基于现有结构演进，我当前倾向的优先级是：

1. 先把固定窗口升级成可插拔算法
2. 再支持方法级差异化限流配置
3. 再让限流和线程池 / 队列 / inflight 状态联动
4. 再补并发控制能力
5. 最后才考虑分布式限流

### 25.10 目前对这部分的结论

当前服务端限流实现作为教学和基础保护已经够用，但如果往更真实的生产场景演进，最自然的路线不是推翻重写，而是沿着现有的：

- 过滤器入口
- 运行时配置
- 限流器管理器
- 限流算法接口

这四层结构逐步扩展。
