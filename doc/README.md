# doc 阅读说明

当前文档只分成 3 个文件夹，避免层级太深影响查找：

## 1. `01-overview`

这一组建议先看，负责建立整体认识。

推荐顺序：
1. [01-rpc-roadmap.md](./01-overview/01-rpc-roadmap.md)
2. [02-project-structure-guide.md](./01-overview/02-project-structure-guide.md)
3. [03-current-usage-guide.md](./01-overview/03-current-usage-guide.md)
4. [04-rpc-project-guide-v1.md](./01-overview/04-rpc-project-guide-v1.md)

适合场景：
1. 第一次看这个项目
2. 先建立整体结构认识
3. 先知道当前项目做到哪一步

## 2. `02-architecture`

这一组按专题拆开讲技术细节。

推荐顺序：
1. [01-rpc-consumer-call-chain.md](./02-architecture/01-rpc-consumer-call-chain.md)
2. [02-rpc-provider-call-chain.md](./02-architecture/02-rpc-provider-call-chain.md)
3. [03-rpc-config-system.md](./02-architecture/03-rpc-config-system.md)
4. [04-rpc-spi-and-extension.md](./02-architecture/04-rpc-spi-and-extension.md)
5. [05-rpc-resilience-design.md](./02-architecture/05-rpc-resilience-design.md)
6. [06-rpc-spring-integration.md](./02-architecture/06-rpc-spring-integration.md)
7. [07-rpc-protocol-design.md](./02-architecture/07-rpc-protocol-design.md)
8. [08-rpc-transport-design.md](./02-architecture/08-rpc-transport-design.md)

适合场景：
1. 精读源码
2. 理解调用链和设计取舍
3. 为后续继续改造做准备

## 3. `03-interview`

这一组是面试和展示材料。

推荐顺序：
1. [01-rpc-resume-version.md](./03-interview/01-rpc-resume-version.md)
2. [02-rpc-project-highlights-final.md](./03-interview/02-rpc-project-highlights-final.md)
3. [03-rpc-interview-kit.md](./03-interview/03-rpc-interview-kit.md)
4. [04-rpc-interview-qa-lite.md](./03-interview/04-rpc-interview-qa-lite.md)
5. [05-rpc-interview-topic-map.md](./03-interview/05-rpc-interview-topic-map.md)

适合场景：
1. 写简历
2. 面试前准备
3. 快速复盘项目亮点和追问点

## 4. 最推荐的阅读路线

如果你现在是第一次系统地看这个项目，建议按下面这条路线：

1. 先看 `01-overview`
2. 再看 `02-architecture`
3. 最后看 `03-interview`

这样顺序最自然：
1. 先知道项目整体是什么
2. 再理解技术细节
3. 最后把它整理成面试表达

## 5. 按目标选择阅读路线

### 5.1 想快速看懂项目全貌

适合场景：
1. 第一次系统看这个项目
2. 先建立整体认识
3. 不急着立刻精读源码

推荐顺序：
1. [01-rpc-roadmap.md](./01-overview/01-rpc-roadmap.md)
2. [02-project-structure-guide.md](./01-overview/02-project-structure-guide.md)
3. [04-rpc-project-guide-v1.md](./01-overview/04-rpc-project-guide-v1.md)
4. [01-rpc-consumer-call-chain.md](./02-architecture/01-rpc-consumer-call-chain.md)
5. [02-rpc-provider-call-chain.md](./02-architecture/02-rpc-provider-call-chain.md)

读完这 5 份，基本就能知道：
1. 项目做到哪一步
2. 分层结构是什么
3. consumer（消费端）/provider（提供端）主链路怎么走

### 5.2 想准备面试和项目介绍

适合场景：
1. 写简历
2. 面试前快速复盘
3. 准备项目介绍和追问

推荐顺序：
1. [01-rpc-resume-version.md](./03-interview/01-rpc-resume-version.md)
2. [02-rpc-project-highlights-final.md](./03-interview/02-rpc-project-highlights-final.md)
3. [03-rpc-interview-kit.md](./03-interview/03-rpc-interview-kit.md)
4. [04-rpc-interview-qa-lite.md](./03-interview/04-rpc-interview-qa-lite.md)
5. [05-rpc-interview-topic-map.md](./03-interview/05-rpc-interview-topic-map.md)

如果时间特别少，至少先看前 3 份。

### 5.3 想继续改代码或继续重构

适合场景：
1. 继续做功能
2. 继续做结构优化
3. 想先明确代码落点

推荐顺序：
1. [02-project-structure-guide.md](./01-overview/02-project-structure-guide.md)
2. [03-rpc-config-system.md](./02-architecture/03-rpc-config-system.md)
3. [04-rpc-spi-and-extension.md](./02-architecture/04-rpc-spi-and-extension.md)
4. [05-rpc-resilience-design.md](./02-architecture/05-rpc-resilience-design.md)
5. [07-rpc-protocol-design.md](./02-architecture/07-rpc-protocol-design.md)
6. [08-rpc-transport-design.md](./02-architecture/08-rpc-transport-design.md)

这条路线更适合带着“我要改哪里”的问题去读。

## 6. 如果时间很少

如果你现在时间非常少，只看下面这 4 份就够：

1. [04-rpc-project-guide-v1.md](./01-overview/04-rpc-project-guide-v1.md)
2. [01-rpc-consumer-call-chain.md](./02-architecture/01-rpc-consumer-call-chain.md)
3. [02-rpc-provider-call-chain.md](./02-architecture/02-rpc-provider-call-chain.md)
4. [02-rpc-project-highlights-final.md](./03-interview/02-rpc-project-highlights-final.md)

这样至少能同时覆盖：
1. 整体结构
2. 主链路
3. 项目亮点表达
