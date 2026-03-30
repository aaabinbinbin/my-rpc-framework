# 当前项目怎么跑起来

## 1. 这篇文档适合谁

适合：
1. 还没真正跑过项目
2. 想先建立“项目是活的”这个感觉
3. 不想一开始就啃源码

## 2. 当前示例里谁调用谁

当前示例最简单的关系是：

```text
example-consumer  调用  example-provider
```

共同依赖：

```text
example-api
```

## 3. 这个调用表面上长什么样

在 consumer 里，调用大概是：

```java
helloService.sayHello("consumer")
```

你看到的像本地方法。

但内部其实是：
1. consumer 拿到代理对象
2. 代理对象发 RPC 请求
3. provider 收到请求
4. provider 执行 `HelloServiceImpl`
5. 返回结果

## 4. provider 端做什么

provider 做两件事：
1. 启动服务
2. 暴露 `HelloServiceImpl`

从概念上看就是：

`我这里有一个真的服务实现，别人可以远程调我。`

## 5. consumer 端做什么

consumer 做两件事：
1. 拿到 `HelloService` 的代理
2. 像本地一样调用方法

从概念上看就是：

`我这里没有真的实现，但我拿到了一个“像实现一样能调用”的代理。`

## 6. 当前项目里最值得先运行的体验

如果你真的要感受这个项目，建议先体验这件事：

1. 启动 provider
2. 再启动 consumer
3. 看 consumer 是否能打印 provider 返回的结果

只要这一步跑通，你对项目的感觉就会从“很多代码”变成“哦，它真的就是远程调用”。

## 7. 运行时你心里要知道什么

你不必一开始知道每个类。

但你最好知道这几件事：
1. `HelloService` 是契约
2. `HelloServiceImpl` 是 provider 真正执行的类
3. consumer 里拿到的是代理，不是实现
4. 中间靠注册中心、网络传输、协议编解码把调用串起来

## 8. 看日志时怎么理解

如果以后你运行项目时看到一堆日志，不要慌。

优先按这条思路理解：
1. 服务是否注册成功
2. consumer 是否找到 provider 地址
3. 请求是否发出
4. provider 是否执行到目标方法
5. 响应是否成功返回

## 9. 第一遍运行的目标

第一遍不是为了排查所有细节，而是为了确认：

`consumer 的一个方法调用，真的能跑到 provider 去执行。`

只要这个感觉建立起来，后面看文档会轻松很多。
