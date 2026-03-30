# doc 阅读说明

这套文档现在按“第一次接触这个项目的人”来组织。

如果你现在还是小白，不要从源码细节开始啃。建议按下面顺序读。

## 1. 第一次看项目，先读哪几篇

推荐顺序：
1. [01-rpc-roadmap.md](./01-overview/01-rpc-roadmap.md)
2. [05-project-visual-guide.md](./01-overview/05-project-visual-guide.md)
3. [02-project-structure-guide.md](./01-overview/02-project-structure-guide.md)
4. [03-current-usage-guide.md](./01-overview/03-current-usage-guide.md)
5. [09-helloService-call-trace.md](./02-architecture/09-helloService-call-trace.md)

读完这 5 篇，你至少会弄清楚：
1. 这个项目到底是干什么的
2. 目录为什么这样分
3. provider 和 consumer 是什么
4. 一次 RPC 调用到底怎么跑

## 2. 文档分组怎么理解

### 2.1 `01-overview`

这一组负责“先把项目看懂”。

适合你还不熟悉 RPC 的时候看，重点是：
1. 先认识项目整体
2. 先知道模块是干什么的
3. 先知道怎么跑起来

### 2.2 `02-architecture`

这一组负责“再把核心原理讲明白”。

适合你已经知道项目结构后，继续往下理解：
1. consumer 怎么发请求
2. provider 怎么处理请求
3. 配置、扩展、协议、传输、容错分别做什么

### 2.3 `03-interview`

这一组负责“把项目讲给别人听”。

适合你已经基本理解项目后，再去准备：
1. 简历怎么写
2. 面试里怎么介绍
3. 常见追问怎么答

## 3. 对小白最友好的阅读路线

### 第一遍：只看整体，不抠源码

1. [01-rpc-roadmap.md](./01-overview/01-rpc-roadmap.md)
2. [05-project-visual-guide.md](./01-overview/05-project-visual-guide.md)
3. [03-current-usage-guide.md](./01-overview/03-current-usage-guide.md)

### 第二遍：只看一条主链路

1. [09-helloService-call-trace.md](./02-architecture/09-helloService-call-trace.md)
2. [01-rpc-consumer-call-chain.md](./02-architecture/01-rpc-consumer-call-chain.md)
3. [02-rpc-provider-call-chain.md](./02-architecture/02-rpc-provider-call-chain.md)

### 第三遍：再看专题

1. [03-rpc-config-system.md](./02-architecture/03-rpc-config-system.md)
2. [06-rpc-spring-integration.md](./02-architecture/06-rpc-spring-integration.md)
3. [05-rpc-resilience-design.md](./02-architecture/05-rpc-resilience-design.md)
4. [07-rpc-protocol-design.md](./02-architecture/07-rpc-protocol-design.md)
5. [08-rpc-transport-design.md](./02-architecture/08-rpc-transport-design.md)
6. [04-rpc-spi-and-extension.md](./02-architecture/04-rpc-spi-and-extension.md)

## 4. 如果你现在只想知道“项目能干嘛”

只看这 3 篇：
1. [01-rpc-roadmap.md](./01-overview/01-rpc-roadmap.md)
2. [05-project-visual-guide.md](./01-overview/05-project-visual-guide.md)
3. [03-current-usage-guide.md](./01-overview/03-current-usage-guide.md)

## 5. 如果你现在只想知道“代码怎么跑”

只看这 4 篇：
1. [03-current-usage-guide.md](./01-overview/03-current-usage-guide.md)
2. [09-helloService-call-trace.md](./02-architecture/09-helloService-call-trace.md)
3. [01-rpc-consumer-call-chain.md](./02-architecture/01-rpc-consumer-call-chain.md)
4. [02-rpc-provider-call-chain.md](./02-architecture/02-rpc-provider-call-chain.md)

## 6. 如果你现在只想准备面试

只看这 3 篇：
1. [01-rpc-resume-version.md](./03-interview/01-rpc-resume-version.md)
2. [02-rpc-project-highlights-final.md](./03-interview/02-rpc-project-highlights-final.md)
3. [04-rpc-interview-qa-lite.md](./03-interview/04-rpc-interview-qa-lite.md)

## 7. 最后一句提醒

这套文档是按“先建立感觉，再理解调用链，最后再看抽象设计”来写的。

不要一上来就钻 `transport`、`protocol`、`spi`，那样很容易把自己看乱。
