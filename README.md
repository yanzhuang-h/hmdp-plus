# 寻味 Taste · 本地生活服务平台

> 高并发秒杀交易 · 多级缓存 · 异步下单与对账补偿 · MCP 智能客服

寻味 Taste 面向商铺浏览、优惠券领取与秒杀下单等本地生活场景，使用 Java 17、Spring Boot 3 和 Vue 3 构建。项目围绕秒杀交易链路，结合 Redis + Lua 库存预扣、Kafka 异步下单、ShardingSphere 分库分表和对账补偿处理高并发请求；通过多级缓存、布隆过滤器与入口限流承接热点访问。

平台同时提供 AI 客服：将业务查询与订单诊断封装为工具，通过 Spring AI 支持应用内多轮对话，并通过 MCP Server 向外部 AI 客户端提供工具调用能力，结合平台规则检索回答业务问题。

## 业务功能

| 模块 | 功能 |
|---|---|
| 用户与会员 | 验证码登录、用户信息、会员等级、签到与签到统计 |
| 商铺与内容 | 商铺详情、分类查询、名称搜索、探店笔记、点赞与关注 |
| 优惠券 | 普通券与秒杀券查询、活动时间与会员资格校验、库存管理 |
| 秒杀订单 | 准入令牌、库存预扣、异步创建订单、结果查询与取消订单 |
| 订阅通知 | 秒杀订阅、到券提醒、库存回流后的候补处理 |
| 智能客服 | 商铺与券查询、本人订单查询、平台规则问答、订单异常诊断 |

## 核心设计

### 秒杀交易与一致性

```text
领取准入令牌
    ↓
入口限流 → 一次性令牌校验 → 活动与会员资格校验
    ↓
Redis + Lua：检查库存与已购记录 → 原子预扣 → 写入扣减流水
    ↓
Kafka 异步下单 → 数据库条件扣减 → 保存订单、路由与对账日志
    ↓
订单结果查询

异常路径：发送失败 / 消费失败 / 消息过期 → 回滚处理
对账路径：检查 Redis 流水与数据库记录 → 补齐流水 / 清理异常状态
```

- **原子预扣**：Lua 在一次脚本执行中完成活动校验、库存检查、重复购买检查、扣减及流水记录，使用 Redis 时间判断活动窗口。
- **异步落库**：Kafka 承接下单消息，消费端以 `stock > 0` 为条件扣减数据库库存，保存订单及相关记录。入口返回订单号后，由查询接口获取落库结果。
- **多层防重**：一次性准入令牌、Redis 已购集合、消息 UUID 防重与正常订单检查分别处理请求重放、重复购买和消息重复执行。
- **失败处理**：发送或消费失败时调用回滚组件，按场景清理购买资格、删除 Redis 库存缓存并记录恢复流水，后续按需重新加载库存。
- **对账与告警**：对账服务检查扣减流水和数据库日志，处理遗漏状态；回滚组件提供退避重试、失败日志和告警。
- **分片与路由**：ShardingSphere 配置两库两表，订单按用户与券维度分片，配套订单路由表和雪花 ID。

### 热点查询与多级缓存

- **秒杀券详情**：Caffeine L1 → Redis L2 → MySQL，Redis 命中后回填本地缓存，TTL 按活动结束时间计算。
- **独立库存 Key**：券详情与预扣库存分开存储，实时库存通过 Redis + Lua 操作。
- **防穿透与重建**：布隆过滤器、空值缓存和互斥重建减少无效回源及热点缓存失效时的数据库压力。
- **广播失效**：业务更新后清理本机缓存并发布 Kafka 失效事件，各实例通过独立消费者组清理自己的本地缓存，推动多实例缓存收敛。
- **入口流控**：按令牌签发、秒杀下单等场景组合准入凭证、令牌桶、滑动窗口和分级惩罚策略。

### 消息与工程组件

- Kafka 消息封装、发送结果回调、手动确认，以及缓存失效链路的重试与死信处理。
- Redisson 分布式锁及 `@ServiceLock` 注解，支持读锁、写锁、公平锁和可重入锁。
- 注解式防重复执行、Redis Key 管理和 HashTag 同槽设计。
- 延迟提醒、订阅处理、库存回流与店铺买家统计。
- Micrometer Tracing + Brave 链路埋点、关联日志及 Prometheus 指标。

## AI 客服与 MCP

### 两种工具入口

```text
平台用户 → 应用内客服 → Spring AI ChatClient → Tool Calling → 业务工具
外部 AI 客户端 → MCP Server（SSE + JSON-RPC）──────────────→ 同一批工具
```

应用内客服通过 `ChatClient` 注册工具，由模型选择调用；MCP Server 提供标准工具发现与调用接口。两种入口复用业务实现。

| 工具 | 用途 |
|---|---|
| `queryShopById` | 查询商铺名称、地址、评分、人均消费等信息 |
| `queryShopVouchers` | 查询商铺优惠券、库存及活动信息 |
| `queryOrderStatus` | 查询当前登录用户本人的订单状态 |
| `queryPlatformRule` | 检索优惠券、秒杀、订单等平台规则 |
| `diagnoseOrderIssue` | 聚合订单、对账、券与商铺信息，诊断订单异常 |

### 上下文、规则与诊断

- **多轮对话**：使用 `ChatMemory` 和消息窗口维护会话上下文，当前为单实例内存实现。
- **订单权限**：用户身份通过服务端 `ToolContext` 注入，工具检查订单归属；越权与不存在统一返回“未找到”。缺少可信用户身份的外部 MCP 调用不能读取私有订单。
- **规则检索 RAG**：加载 5 份 Markdown 中的 27 条规则，结合中文 bigram、同义词扩展、词频与逆文档频率打分、标题加权和阈值过滤，返回相关规则供模型回答。
- **诊断编排**：复合工具在代码中组织查询依赖，并行获取订单与对账信息，继续查询券及商铺，结合规则生成诊断材料，记录步骤耗时与失败信息并支持部分降级。

## 技术栈

| 类别 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.5.4、MyBatis-Plus |
| 数据存储 | MySQL、ShardingSphere 5.3.2 |
| 缓存与协调 | Redis、Caffeine、Redisson |
| 消息 | Kafka |
| 大模型与工具 | Spring AI 1.0.9、DeepSeek（OpenAI 兼容接口）、MCP（SSE） |
| 可观测 | Micrometer Tracing、Brave、Prometheus |
| 前端 | Vue 3、Vite、Element Plus、Pinia |
| 验证与压测 | JUnit、JMeter、Node.js MCP 验证脚本 |

## 项目结构

```text
hmdp-plus/
├── hmdp-core-service/             # 业务接口、订单、缓存、MCP 与客服
├── hmdp-common/                   # 公共工具与基础配置
├── hmdp-parameter/                # 参数与数据传输对象
├── hmdp-sharding/                 # 分库分表支持
├── hmdp-redis-tool-framework/     # Redis 封装、Key 管理与限流
├── hmdp-redisson-framework/       # 分布式锁、防重、布隆过滤器与延迟队列
├── hmdp-mq-framework/             # 消息生产与消费封装
├── hmdp-id-generator-framework/   # 分布式 ID
├── hmdp-vue3/                     # Vue 3 前端
├── sql/                          # 数据库初始化脚本
├── perf/                         # 压测计划、结果与 MCP 验证脚本
└── docs/                         # 架构设计与功能说明
```

## 运行方式

### 1. 准备依赖与配置

准备 JDK 17、Maven、MySQL、Redis 和 Kafka；运行前端及验证脚本还需 Node.js，前端使用 pnpm。

1. 执行 [创建数据库脚本](sql/1_create_database.sql)，再分别向 `hmdp_0`、`hmdp_1` 导入对应的 SQL 文件。
2. 在 `hmdp-core-service/src/main/resources/shardingsphere.yaml` 中配置数据库连接。
3. 在 `hmdp-core-service/src/main/resources/application.yml` 中检查 Redis、Kafka 和应用配置。
4. 通过环境变量 `DEEPSEEK_API_KEY` 配置模型密钥。

PowerShell 示例（在启动后端的同一终端执行）：

```powershell
$env:DEEPSEEK_API_KEY = "你的模型密钥"
```

Linux / macOS：

```bash
export DEEPSEEK_API_KEY="你的模型密钥"
```

### 2. 启动后端

在项目根目录执行：

```bash
mvn clean install -DskipTests
mvn -pl hmdp-core-service spring-boot:run
```

后端默认地址：`http://localhost:8085`。也可在 IDE 中运行 `org.javaup.HmDianPingApplication`。

### 3. 启动前端

```bash
cd hmdp-vue3
pnpm install
pnpm dev
```

前端默认地址：`http://localhost:5173`，`/api` 请求代理至后端。图片请求 `/imgs` 当前代理至 `http://localhost:8080`，需按本地静态资源服务调整。

### 4. 验证 MCP 与客服

在项目根目录执行：

```bash
node perf/mcp-verify.mjs http://localhost:8085
```

脚本验证 SSE 连接、协议握手、工具清单和商铺、优惠券工具调用。当前注册 5 个工具；业务返回内容取决于本地初始化数据。

应用内客服入口为 `POST /ai/chat`，使用登录后的 `authorization` 请求头，提交 `message` 与 `sessionId` 参数。同一会话使用相同的 `sessionId`。例如可询问“1 号商铺有什么优惠券”或“为什么抢到了却没有订单”。

## 压测记录

历史 JMeter 测试中，应用与压测工具部署在同一台机器。以下数据对应读接口 `/shop-type/list`：

| 并发数 | QPS | 平均耗时 | P99 |
|---|---:|---:|---:|
| 100 | 4,199 | 19.9 ms | 46 ms |
| 200 | 3,806 | 44.1 ms | 114 ms |
| 400 | 3,592 | 91.0 ms | 191 ms |

该测试条件下，100 并发组吞吐最高，继续加压后吞吐下降、延迟上升。结果反映读接口表现，秒杀链路另有一次性令牌和限流保护验证。完整环境、脚本与历史结果见 [压测报告](perf/压测报告.md) 和 [数据汇总](perf/summary.md)。

## 相关文档

| 文档 | 内容 |
|---|---|
| [MCP 与智能客服](docs/MCP与智能客服.md) | 接入过程、工具复用、权限与序列化排障记录 |
| [RAG 设计](docs/RAG设计.md) | 平台规则组织与检索设计 |
| [跨工具编排设计](docs/跨工具编排设计.md) | 订单诊断依赖与执行流程 |
| [AI 功能自验手册](docs/AI功能自验手册.md) | 本地功能验证步骤 |
| [压测报告](perf/压测报告.md) | 测试环境、场景与结果 |

部分文档记录了开发过程中的历史版本，当前工具与功能以源码及本 README 为准。

## 许可证

[Apache License 2.0](LICENSE)
