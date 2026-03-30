# 先把最小示例看活，再回来看原理

## 1. 为什么我建议你先建立“项目是活的”这种感觉

很多人看框架项目时，一上来就想把源码看懂。

这当然没有错，但对于零基础读者来说，会非常容易出问题。

问题不在于你不够聪明，而在于脑子里缺少一个非常重要的东西：

`运行画面。`

如果你没有运行画面，那么你看到：

1. `@RpcReference`
2. `RpcConsumerBootstrap`
3. `RpcClientInvocationExecutor`
4. `RpcRequestDispatcher`
5. `RpcProtocolEncoder`
6. `RpcNettyClient`

这些类时，很容易觉得它们只是一些技术点。

但如果你先有了“项目是怎么活起来的”这个画面，你就会知道：

1. 谁在调用
2. 谁在提供服务
3. 调用为什么能过去
4. 结果为什么能回来

所以这一篇要做的事，不是教你运行命令，而是帮你建立最小闭环感。

## 2. 当前项目最小闭环到底是什么

先只看这三个模块：

```text
example-api
example-provider
example-consumer
```

最小闭环就是：

```text
example-consumer
  调用
example-provider
```

中间靠的是：

```text
rpc-core + rpc-spring + rpc-spring-boot-starter
```

也就是说：

1. `example-api` 提供接口契约
2. `example-provider` 提供服务实现
3. `example-consumer` 发起调用
4. 框架层负责把“调用”和“执行”真正连起来

## 3. 你现在运行这个项目时，应该观察什么

先不要去追日志里每一行。

先只看最本质的事情有没有发生。

### 在 provider 这一边

你应该确认：
1. provider 应用启动了
2. provider 的服务对象被框架接管了
3. provider 已经准备好接收远程请求了

### 在 consumer 这一边

你应该确认：
1. consumer 应用启动了
2. `@RpcReference` 对应的字段不再是空值
3. 调用 `helloService.sayHello(...)` 时没有走本地实现
4. 最后真的拿到了结果

## 4. 你脑子里应该有怎样的运行画面

建议你现在就想象这幅画面：

```text
consumer 启动
  -> 它拿到了一个代理对象
  -> 业务代码调用代理对象的方法
  -> 框架把方法调用组织成一次请求
  -> 请求发给 provider
  -> provider 收到请求并执行本地服务对象
  -> 结果再返回给 consumer
```

这幅画面看起来很简单，但它非常重要。

因为你后面所有要学的东西，基本都只是这幅画面的不同细化版本。

## 5. 这一篇不是要你马上懂实现，而是让你接受闭环

你现在不用急着回答：

1. 代理是怎么创建的
2. 请求是怎么编码的
3. provider 是怎么找本地服务对象的

这些问题后面都会讲。

这一篇你最重要的任务只有一个：

`接受这个项目最小闭环是真的存在的。`

也就是说，consumer 真不是在本地调了个假方法，provider 也真不是凭空知道要执行哪个方法。

它们之间确实被框架完整地接起来了。

## 6. 下一步该看什么

现在你有了“最小闭环”的感觉，下一步就该看：

`仓库为什么要拆成这些模块。`

下一篇看：[04-repo-map.md](./04-repo-map.md)
