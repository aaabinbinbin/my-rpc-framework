# 面试表达入口

## 1. 三句话版本

这个项目是一个 Java RPC 框架，目标是让 consumer 像调用本地接口一样调用 provider 上的远程服务。

consumer 侧通过 Spring 注入代理对象，代理把方法调用封装成 `RpcRequest`，经过过滤器、限流熔断、服务发现、负载均衡、cluster、重试和 Netty 传输发到 provider。

provider 侧解码请求后，通过本地注册表找到服务实现对象，执行 provider 过滤器和真实业务方法，再把结果封装为 `RpcResponse` 返回。

## 2. 推荐展开顺序

1. 先讲项目目标。
2. 再讲模块拆分。
3. 再讲一次调用链。
4. 再讲治理能力挂在哪里。
5. 最后讲自己优化过或能深入解释的点。

## 3. 可以重点讲的类

1. `RpcSpringManager`：Spring 生命周期集成。
2. `RpcInvocationHandler`：consumer 代理入口。
3. `RpcClientInvocationExecutor`：consumer 调用编排。
4. `RpcServiceResolver`：服务发现和地址选择。
5. `RpcNettyClient`：网络发送。
6. `RpcRequestDispatcher`：provider 请求分发。
7. `RpcRequestExecutor`：provider 本地执行。
8. `LocalRegistryImpl`：provider 进程内服务映射。

## 4. 深入追问入口

| 追问 | 回答方向 |
| --- | --- |
| 为什么要动态代理 | 业务代码只依赖接口，框架在代理里接管调用 |
| requestId 有什么用 | 对应一次真实请求，用于响应回填 |
| traceId 有什么用 | 串联一次业务调用链路 |
| 熔断和限流区别 | 限流控制请求进入速度，熔断根据失败状态短路调用 |
| cluster 和 retry 区别 | retry 是重试动作，cluster 决定失败策略和是否换实例 |
| provider 怎么找到真实对象 | 通过 serviceName 从 `LocalRegistryImpl` 查服务实现 |
| Spring Boot 怎么接入 | 自动装配创建配置、manager、可观测端点，并扫描 `@RpcService` |

## 5. 已有输出文档

更完整的表达材料看：

1. [`../04-output/01-project-summary.md`](../04-output/01-project-summary.md)
2. [`../04-output/02-resume-and-interview.md`](../04-output/02-resume-and-interview.md)
3. [`../04-output/05-interview-playbook.md`](../04-output/05-interview-playbook.md)
4. [`../04-output/06-technical-interview-notes.md`](../04-output/06-technical-interview-notes.md)

