# hmdp-plus

> 高并发本地生活后端 · 秒杀一致性链路 · 多级缓存 · 消息可靠性 · MCP 工具化 AI

---

## 这是什么

一个**本地生活服务**方向的后端项目，围绕「优惠券秒杀」与「热点查询」两个高并发场景，实现从**入口限流 → Redis 原子扣减 → Kafka 异步下单 → 分库分表落库 → 定时对账补偿**的完整链路，并配套多级缓存、布隆过滤器、分布式锁、延迟队列等基础组件。

在此之上增加了 **MCP（Model Context Protocol）工具化能力**：把商户、优惠券、订单查询封装成受控工具，供 AI 客户端调用，实现带上下文的智能客服。

**本仓库用途**：个人学习与求职展示。用来证明后端工程能力与中间件理解，而非生产交付。

---

## 来源与许可

| 项 | 说明 |
|---|---|
| 原项目 | [hmdp-plus](https://gitee.com/java-up-up/hmdp-plus)（作者：阿星不是程序员） |
| 许可证 | **Apache License 2.0**（见 [LICENSE](LICENSE)） |
| 本仓库性质 | 基于原项目的**二次开发**，保留原始许可与版权声明 |
| 修改范围 | 见下方「本仓库的改动」 |

> 依据 Apache 2.0 第 4 条，本仓库保留 `LICENSE` 与原始版权声明，并在下节明确标注所做的修改。未使用原作者商标或名义进行推广。

---

## 本仓库的改动

### 后端修复与调优

- **修复 JSON 序列化的 null 语义**：`JsonCustomSerializer` 原实现把 `Number` 类型的 `null` 序列化成 `""`、把 `List` 类型的 `null` 序列化成 `[]`，会让第三方 API 请求体非法；改为输出 `null`。
- **移除硬编码的数字转字符串**：`JacksonCustom` 中强制开启的 `WRITE_NUMBERS_AS_STRINGS` 会导致所有数值序列化为字符串，与上游 API 冲突。
- **修正初始化顺序**：`DelCacheDataInit` 增加 `@DependsOn`，确保「券时间续期」完成后再清缓存，避免按旧时间窗重建缓存。
- **修复上传路径与 SQL 脚本**：上传目录改为前端静态资源目录；修正 `sql/hmdp_0.sql`、`hmdp_1.sql` 中 MySQL 5.7 不支持的排序规则与孤儿字符集还原语句。

### AI 能力（新增）

- **MCP Server**：以标准 MCP 协议（JSON-RPC over SSE）暴露 3 个只读工具
  - `queryShopById` — 查询商铺详情
  - `queryShopVouchers` — 查询商铺优惠券与实时库存
  - `queryOrderStatus` — 查询订单状态
  - 设计约束：只读、不依赖 `UserHolder`、异常统一转友好文本
- **智能客服**：Spring AI `ChatClient` + `ChatMemory`（按会话隔离），支持多轮上下文
- **验证脚本**：`perf/mcp-verify.mjs` 自实现最小 MCP 客户端，验证握手、工具清单与工具实际调用

### 压测与文档（新增）

- 参数化 JMeter 压测计划 + jtl 分析脚本 + 压测报告
- 开发路线图、MCP 架构说明、面试知识库、自验手册

---

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 框架 | Java 17 · Spring Boot 3.5 |
| 持久层 | MyBatis-Plus · MySQL 5.7 |
| 分库分表 | ShardingSphere（2 库 × 2 表） |
| 缓存 | Redis 6 · Caffeine（L1）· Redisson |
| 消息 | Kafka（生产确认 / 重试 / DLQ / 手动 ack） |
| 分布式协调 | Redisson（读/写/公平/重入锁、延迟队列） |
| AI | Spring AI 1.0.9 · MCP Server · OpenAI 兼容协议接入 DeepSeek |
| 可观测 | Micrometer · Prometheus |
| 前端（配套） | Vue 3 · Vite |

---

## 核心能力

### 后端

| 能力 | 实现要点 |
|---|---|
| 秒杀链路 | Redis + Lua 原子扣减（库存 + 已购集合 + 流水一次完成）→ Kafka 异步下单 → 分库分表落库 |
| 数据一致性 | 对账日志表 + 定时对账任务 + 回滚失败日志 + 告警；覆盖「扣减成功但订单失败」「消费延迟」等窗口期 |
| 缓存治理 | L1 Caffeine + L2 Redis；布隆过滤器 + 空值缓存防穿透；逻辑过期 + 互斥锁 + 异步重建防击穿；Kafka 广播失效保证多实例 L1 一致 |
| 消息可靠性 | 发布确认、指数退避重试、死信队列、手动提交 offset、消费幂等 |
| 入口流控 | 令牌签发 + 令牌桶 + 滑动窗口 + 分级惩罚策略 |
| 分布式锁 | Redisson 读/写/公平/重入锁 + `@ServiceLock` 注解 |
| 延迟队列 | 到券提醒、订单超时关闭 |
| 全局 ID | 雪花算法（含时钟回拨检测），适配分库分表 |

### AI 增强

| 能力 | 状态 |
|---|---|
| MCP Server（3 工具） | ✅ 已实测：工具清单、工具调用、错误路径 |
| 单轮对话（工具调用） | ✅ 已实测：真实数据回填，非模型编造 |
| 多轮对话（上下文记忆） | ✅ 已实测：可正确理解代词指代 |

> AI 部分的边界是「**复用后端已有数据与接口**」，不引入向量库、工作流状态机、评测框架等独立基础设施。详见 [开发路线图](docs/hmdp-plus-开发路线图.md)。

---

## 压测数据

环境：单机（24 核），应用与压测工具同机，数据偏保守。

| 场景 | 并发 | QPS | P99 | 错误率 |
|---|---|---|---|---|
| 无缓存接口 | 100 | **4,199** | 46ms | 0% |
| 无缓存接口 | 400 | 3,592 | 191ms | 0% |
| **L1+L2 缓存接口** | 100 | **6,825** | 48ms | 0% |

- 吞吐拐点在 **100 并发**，继续加压 QPS 反而下降
- 多级缓存带来 **+62.6% QPS**，平均耗时下降 39%

完整方法与数据见 [压测报告](perf/压测报告.md)。

---

## 快速开始

### 依赖

MySQL 5.7、Redis 6、Kafka（本地或用 Docker）。数据库脚本见 `sql/`。

### 配置

```bash
# 1. 数据库连接（按需修改）
hmdp-core-service/src/main/resources/shardingsphere.yaml  →  jdbcUrl / username / password

# 2. AI 能力所需的模型密钥（不配置则仅 AI 功能不可用，其余功能正常）
#    通过环境变量注入，切勿写入配置文件
export DEEPSEEK_API_KEY=sk-xxxxxxxx      # Linux / macOS
setx DEEPSEEK_API_KEY "sk-xxxxxxxx"      # Windows（设置后需重启 IDE）

# 或使用 IDEA：Run → Edit Configurations → VM options
-DDEEPSEEK_API_KEY=sk-xxxxxxxx
```

### 启动

```bash
mvn clean install -DskipTests
mvn -pl hmdp-core-service spring-boot:run
```

服务默认端口 `8085`。

### 验证 MCP

```bash
node perf/mcp-verify.mjs http://localhost:8085
```

期望输出：握手成功、工具清单返回 3 个工具、工具调用返回真实业务数据。

---

## 文档

| 文档 | 内容 |
|---|---|
| [开发路线图](docs/hmdp-plus-开发路线图.md) | 后端深化计划、AI 能力边界、验收标准 |
| [压测报告](perf/压测报告.md) | 环境、方案、6 组数据、局限性 |
| [MCP 与智能客服](docs/MCP与智能客服.md) | 架构、端点、工具 schema、5 类序列化问题排查过程 |
| [AI 功能自验手册](docs/AI功能自验手册.md) | 配置密钥后的 4 步验证流程 |
| [面试知识库](docs/项目盘点与面试知识库.md) | 能力盘点、简历措辞分档、面试话术 |

---

## 已知限制

- 压测为**单机同机**，应用与压测工具争夺 CPU，数据偏保守，不代表生产极限
- **Redis GEO 附近商户**功能已实现但当前被禁用（`x/y` 入参被置空），未参与验收
- **评论模块**仅有 Controller 骨架，未实现
- AI 部分：`ChatMemory` 为单实例内存实现，多实例部署需替换为 Redis 版
- 未做：全链路追踪、多活容灾、生产级安全加固

---

## 致谢

原项目 [hmdp-plus](https://gitee.com/java-up-up/hmdp-plus) 提供了完整的业务骨架与中间件实践基础，本项目在其之上做了修复、补全与 AI 能力扩展。
