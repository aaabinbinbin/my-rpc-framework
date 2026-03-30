# 这个项目到底在做什么

## 1. 先不要急着看技术词，先看问题本身

如果我们不用任何技术术语，直接用最朴素的话来描述，这个项目在做的事其实是：

`让一个程序，像调用本地方法一样，去调用另一个程序里的方法。`

这句话就是整个项目的核心。

如果你现在对 RPC 还没有太多感觉，那么你只需要先抓住这里的两层意思：

1. 调用方写出来的代码，看起来像本地调用
2. 但真实执行逻辑其实发生在另一边

也就是说，这个项目在做的，不是“把几个技术词拼在一起”，而是在搭一座桥：

`把业务层想要的“本地调用体验”和底层真实发生的“远程调用过程”连起来。`

## 2. 如果没有 RPC 框架，你会遇到什么问题

假设你有两个应用：

1. A 应用里有业务逻辑，想调用 B 应用提供的一个服务
2. B 应用里真正实现了那个服务

如果没有 RPC 框架，你就得自己处理很多事情：

1. B 在哪台机器上
2. B 用哪个端口
3. 请求数据长什么样
4. 怎么把对象变成网络字节
5. B 收到字节后怎么还原回来
6. B 怎么知道该调哪个方法
7. 失败了怎么办
8. B 很忙的时候怎么办

你会发现，业务本身可能只是一句：

```java
sayHello("consumer")
```

但为了把这句真正送过去执行，中间要铺很多层路。

这个项目做的事情，就是把这些重复、琐碎、又特别适合抽出来统一处理的事情，做成一个框架。

## 3. 先看这个项目里最小的业务闭环

当前仓库里，最小闭环其实就三个示例模块：

```text
example-api
example-provider
example-consumer
```

这三个模块分别扮演不同角色。

### `example-api`

这里放的是公共接口，也就是服务契约。

比如：

```java
public interface HelloService {
    String sayHello(String name);
}
```

它的意义是：

1. provider 和 consumer 都依赖同一份接口定义
2. 双方都按这份契约来沟通

### `example-provider`

这里放的是真正提供服务的一方。

比如：

```java
@RpcService(HelloService.class)
public class HelloServiceImpl implements HelloService {
    @Override
    public String sayHello(String name) {
        return "Hello, " + name + "!";
    }
}
```

它的意义是：

1. 真正的业务逻辑在这里
2. 当远程请求真的跑到 provider 端，最后就会落到这个类的这个方法上

### `example-consumer`

这里放的是调用服务的一方。

比如：

```java
@RpcReference
private HelloService helloService;

System.out.println(helloService.sayHello("consumer"));
```

它的意义是：

1. 业务层只关心接口，不关心远程调用细节
2. 看起来就像在调一个本地对象

## 4. 现在你要建立的第一个认知

看到上面三段代码以后，你现在最该建立的认知是：

`业务层看到的简单，不等于底层真的简单。`

业务层只看到：

```java
helloService.sayHello("consumer")
```

但真实发生的过程至少包括：

1. `helloService` 并不是真正实现，而是代理对象
2. 代理对象会拦截这次方法调用
3. 框架会把方法调用整理成 `RpcRequest`
4. consumer 会先找到 provider 地址
5. 请求会通过网络发给 provider
6. provider 会把请求落到 `HelloServiceImpl.sayHello(...)`
7. 执行结果会再返回给 consumer

所以你可以把整个项目先理解成一条长链：

`业务调用 -> 框架中间层 -> 远程执行 -> 结果返回`

## 5. 那 `rpc-core`、`rpc-spring` 这些模块又是在做什么

当你接受上面的最小闭环以后，就很容易理解：

### `rpc-core`

这是“真正把远程调用跑通”的核心。

它负责：
1. 代理
2. 服务注册与发现
3. 协议
4. 传输
5. 过滤器
6. 容错
7. 运行时支撑

### `rpc-spring`

这是把 RPC 接进 Spring 生命周期的层。

它负责：
1. 让 `@RpcReference` 自动注入
2. 让 `@RpcService` 自动发布

### `rpc-spring-boot-starter`

这是进一步降低使用成本的自动装配层。

它不是改变主链路，而是让接入更方便。

## 6. 这一篇你现在只要记住什么

如果你现在只记住一句话，那就记住这句：

`这个项目的本质，是让 consumer 看到的“本地调用体验”和底层真实发生的“远程调用流程”接起来。`

下一篇看：[03-run-the-smallest-example.md](./03-run-the-smallest-example.md)
