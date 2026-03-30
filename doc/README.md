# 从 0 到 1 看懂这个 RPC 项目

这套文档是一门按学习顺序组织的课程，不是一组零散的知识点笔记。

如果你以前没有系统看过 RPC 项目，或者虽然写过 Java / Spring 业务代码，但没有完整追过“一个框架项目到底是怎么把一次远程调用跑通”的全过程，那么这套文档就是为你准备的。

整套文档的核心目标只有一个：

`让一个没有项目基础的人，最终能够完整、稳定、清楚地理解这个仓库。`

这里说的“理解”，不是指你记住了多少类名，而是指你最后能够做到下面这些事：

1. 你能说清楚这个项目到底解决什么问题。
2. 你能说清楚一次 RPC 调用从哪里开始，到哪里结束。
3. 你能说清楚为什么仓库要拆成现在这些模块。
4. 你能知道哪些类是入口，哪些类是中枢，哪些类是支撑层。
5. 你能带着明确顺序去看源码，而不是乱点文件。
6. 你能把这个项目讲给别人听，而不是只会说“这里用了 Netty、那里用了 ZooKeeper”。

## 1. 你该怎么使用这套文档

最重要的一点是：

`不要把它当作参考手册来随机查，而要把它当作连续课程按顺序读。`

因为对于零基础读者来说，最大的困难从来都不是某一个知识点太难，而是：

1. 信息出现得太散
2. 术语一次来得太多
3. 还没建立主线，就先掉进细节里
4. 明明每篇都看懂一点，但连不起来

所以我现在把整套内容拆成四个阶段，每个阶段只做那个阶段最该做的事。

## 2. 四个阶段分别做什么

### 第一阶段：先建立感觉

这一阶段不是让你研究底层实现，而是让你先回答最基础的几个问题：

1. 什么是 RPC。
2. 这个项目里 provider、consumer、api 分别是谁。
3. 为什么仓库有 `rpc-core`、`rpc-spring`、`example-*` 这些模块。
4. 这个项目最小闭环到底是什么。

如果第一阶段没过，你后面看协议、传输、扩展、容错时，几乎一定会觉得抽象。

### 第二阶段：先打通一条主线

这一组现在是整套文档里最适合重点阅读的部分，已经按“长文讲解 + 真实源码片段 + 流程图”的方式重写。

建议读法：

1. 先只顺着文字把故事读通，不急着点开源码
2. 第二遍再对照文中的代码片段和源码文件
3. 图看不懂时，先看图下面那一段解释，不要反过来

这一阶段只抓一件事：

`一次 RPC 调用从 consumer 到 provider 的完整链路。`

因为 RPC 项目本质上不是“几个功能点”，而是一条调用链。

如果这条链你没打通，那么：

1. 过滤器会显得像单独存在
2. 配置会显得像单独存在
3. 传输会显得像单独存在
4. Spring 集成也会显得像单独存在

实际上它们都只是这条主线上的不同部分。

### 第三阶段：再回到源码

这一组现在也已经重写成“超详细源码导读版”，不是简短提纲，而是按“阅读顺序 + 关键类抓手 + 术语和类映射”来写。

建议读法：

1. 先读 `01-reading-order.md`，确定第一遍的源码路线
2. 再读 `02-key-classes-annotated.md`，知道每个关键类打开以后重点看哪里
3. 最后读 `03-glossary-and-class-map.md`，把术语和真实类一一对上

到第三阶段你才真正开始“读源码”。

为什么不一开始就读？

因为源码阅读最怕的不是代码多，而是没有路线。

所以到了这个阶段，文档会给你：

1. 明确的阅读顺序
2. 关键类的阅读重点
3. 术语和类的对应关系

这样你在 IDE 里看代码时，不是“随便点开一个类”，而是“知道自己现在正在主链路的哪一段”。

### 第四阶段：最后输出理解结果

这一组也已经重写成“完整输出版”，不是简单模板，而是专门讲：

1. 怎么把项目收束成清楚的整体总结
2. 怎么把主线压缩进简历描述
3. 怎么在面试里稳定介绍项目并接住追问

能看懂项目不等于能讲清楚项目。

所以最后一阶段会帮你把前面的理解整理成：

1. 项目介绍
2. 简历描述
3. 面试表达

## 3. 建议的阅读顺序

### 第一阶段：先建立感觉

1. [00-complete-learning-path.md](./00-complete-learning-path.md)
2. [01-how-to-read-this-course.md](./01-start-here/01-how-to-read-this-course.md)
3. [02-what-this-project-really-does.md](./01-start-here/02-what-this-project-really-does.md)
4. [03-run-the-smallest-example.md](./01-start-here/03-run-the-smallest-example.md)
5. [04-repo-map.md](./01-start-here/04-repo-map.md)

### 第二阶段：先打通一条主线

1. [01-one-rpc-call-overview.md](./02-main-story/01-one-rpc-call-overview.md)
2. [02-consumer-side-deep-dive.md](./02-main-story/02-consumer-side-deep-dive.md)
3. [03-provider-side-deep-dive.md](./02-main-story/03-provider-side-deep-dive.md)
4. [04-spring-integration-story.md](./02-main-story/04-spring-integration-story.md)
5. [05-config-extension-resilience.md](./02-main-story/05-config-extension-resilience.md)
6. [06-protocol-and-transport-story.md](./02-main-story/06-protocol-and-transport-story.md)

### 第三阶段：再回到源码

1. [01-reading-order.md](./03-source-reading/01-reading-order.md)
2. [02-key-classes-annotated.md](./03-source-reading/02-key-classes-annotated.md)
3. [03-glossary-and-class-map.md](./03-source-reading/03-glossary-and-class-map.md)

### 第四阶段：把项目讲出去

1. [01-project-summary.md](./04-output/01-project-summary.md)
2. [02-resume-and-interview.md](./04-output/02-resume-and-interview.md)

## 4. 学习纪律：怎么读才不会费劲

这里有几个非常实际的建议。

### 4.1 第一遍不要追求“精确到每个类”

第一遍最重要的是主线，不是细节。

如果你现在还不能稳定复述“consumer 是怎么把一次方法调用变成 provider 上一次真实执行”的过程，那么这时候去研究 Netty pipeline 细节、协议头字段细节、SPI 加载细节，收益都不高。

### 4.2 看到术语先接受，再逐步精确

比如：

1. `proxy`
2. `bootstrap`
3. `registry`
4. `discovery`
5. `cluster`
6. `protocol`
7. `transport`

这些词你不需要第一眼就完全掌握。

你只需要先知道它们大概在做什么，等它们在主线里多出现几次，你的理解就会变稳。

### 4.3 每读完一篇，都试着自己复述

最有效的检验方法不是“我有没有看完”，而是：

`我能不能不用看原文，用自己的话把这一篇讲出来。`

哪怕讲得不够标准，只要你能复述，说明你开始真正吸收了。

## 5. 如果你现在完全不知道从哪开始

那就不要犹豫，直接从下面三篇开始：

1. [00-complete-learning-path.md](./00-complete-learning-path.md)
2. [02-what-this-project-really-does.md](./01-start-here/02-what-this-project-really-does.md)
3. [01-one-rpc-call-overview.md](./02-main-story/01-one-rpc-call-overview.md)

这三篇是整套文档的真正入口。
