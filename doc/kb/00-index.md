# RPC 项目知识库索引

这层文档不是连续课程，而是查询入口。已经知道大概主线时，从这里查。

## 1. 查什么去哪里

1. 项目一句话、模块、技术栈、启动方式：[`01-project-card.md`](./01-project-card.md)
2. 模块分层和架构地图：[`02-architecture-map.md`](./02-architecture-map.md)
3. 一次 RPC 调用链路：[`03-rpc-call-flow.md`](./03-rpc-call-flow.md)
4. 关键类索引：[`04-key-classes.md`](./04-key-classes.md)
5. 配置、扩展、治理能力：[`05-config-and-extension.md`](./05-config-and-extension.md)
6. Spring / Spring Boot 接入：[`06-spring-integration.md`](./06-spring-integration.md)
7. 常见问题和排障：[`07-troubleshooting.md`](./07-troubleshooting.md)
8. 面试表达入口：[`08-interview-notes.md`](./08-interview-notes.md)
9. AI / 检索上下文：[`09-ai-context.md`](./09-ai-context.md)
10. 知识库维护规则：[`10-maintenance-guide.md`](./10-maintenance-guide.md)

## 2. 和教材层的关系

教材层适合按顺序学：

1. [`../README.md`](../README.md)
2. [`../01-start-here/05-current-implementation-snapshot.md`](../01-start-here/05-current-implementation-snapshot.md)
3. [`../02-main-story/01-one-rpc-call-overview.md`](../02-main-story/01-one-rpc-call-overview.md)
4. [`../03-source-reading/01-reading-order.md`](../03-source-reading/01-reading-order.md)

知识库层适合反向查：

1. 看到一个类名，去 `04-key-classes.md` 查职责。
2. 看到一个配置项，去 `05-config-and-extension.md` 查影响范围。
3. 调用链断了，去 `03-rpc-call-flow.md` 对照阶段。
4. 示例跑不起来，去 `07-troubleshooting.md` 查启动和注册中心。

## 3. 专业审查结论

当前知识库可以承担项目级知识库职责，原因是它已经覆盖了四类核心问题：

1. 项目是什么：`01-project-card.md` 和 `02-architecture-map.md`。
2. 调用怎么流：`03-rpc-call-flow.md`。
3. 源码怎么定位：`04-key-classes.md`。
4. 运行和维护怎么查：`05-config-and-extension.md`、`06-spring-integration.md`、`07-troubleshooting.md`、`10-maintenance-guide.md`。

它不替代教材层。教材层负责连续学习，知识库层负责快速定位和后续维护。
