# MCP Server 与智能客服

> 实现时间：2026-10-21
> 技术栈：Spring AI 1.0.0 + MCP Java SDK + DeepSeek

---

## 一、这是什么

项目新增两项 AI 能力，**复用同一批业务工具**：

| 能力 | 面向谁 | 协议 |
|---|---|---|
| **MCP Server** | 外部 AI 客户端（Claude Desktop / Cursor / 任意 MCP Client） | 标准 MCP（JSON-RPC 2.0 over SSE） |
| **智能客服** | 平台用户（应用内对话） | HTTP + Spring AI ChatClient（Function Calling） |

两者都封装了同样 3 个工具：**查商铺、查优惠券、查订单**。

## 二、架构

```
┌─────────────────────────────┐
│ 外部 AI 客户端                │
│ (Claude Desktop / Cursor)    │
└──────────────┬──────────────┘
               │ MCP 协议 (SSE + JSON-RPC)
               ▼
┌──────────────────────────────────────────┐
│  hmdp-core-service (Spring Boot)          │
│                                           │
│  ┌─────────────────────────────────┐     │
│  │ McpServerConfig                  │     │
│  │  └─ ToolCallbackProvider         │     │
│  │       └─ McpShopTools            │     │
│  │            ├ queryShopById       │     │
│  │            ├ queryShopVouchers   │     │
│  │            └ queryOrderStatus    │     │
│  └─────────────────────────────────┘     │
│                  ▲ 复用                   │
│  ┌───────────────┴─────────────────┐     │
│  │ AiChatConfig                     │     │
│  │  └─ ChatClient (DeepSeek)        │     │
│  │       + ChatMemory(20条窗口)      │     │
│  │       + defaultTools(McpShopTools)│     │
│  └─────────────────────────────────┘     │
│                  ▲                        │
│  ┌───────────────┴─────────────────┐     │
│  │ AiChatController                 │     │
│  │  POST /ai/chat                   │     │
│  └─────────────────────────────────┘     │
│                                           │
│  业务层：IShopService / IVoucherService   │
│          ISeckillVoucherService / I...    │
└──────────────────────────────────────────┘
```

## 三、端点

| 端点 | 方法 | 说明 |
|---|---|---|
| `/sse` | GET | 建立 MCP SSE 流，返回消息端点（含 sessionId） |
| `/mcp/message` | POST | 发送 MCP JSON-RPC 请求（initialize / tools/list / tools/call） |
| `/ai/chat` | POST | 智能客服对话，参数 `message` + `sessionId` |
| `/ai/tool/shop?id=1` | GET | 调试：直接调工具（不经过大模型） |
| `/ai/tool/vouchers?shopId=1` | GET | 调试：查券 |
| `/ai/tool/order?orderId=xxx` | GET | 调试：查订单 |

> `/sse` 与 `/mcp/**` 已在 `MvcConfig` 的 `LoginInterceptor.excludePathPatterns` 中放行，
> 否则外部 AI 客户端没有浏览器登录态会被 401 拦截。

## 四、工具清单

| 工具名 | 参数 | 返回 |
|---|---|---|
| `queryShopById` | `shopId` | 名称、商圈、地址、评分、人均消费、营业时间、销量 |
| `queryShopVouchers` | `shopId` | 面额、售价、状态；秒杀券额外含库存、活动起止时间、最低会员等级 |
| `queryOrderStatus` | `orderId` | 订单状态、对账状态、下单/支付/核销/退款时间 |

**设计约束**（写进了代码注释）：
1. **只读**，不做写操作，避免 AI 误改业务数据；
2. **不依赖 `UserHolder`**（MCP 调用没有登录上下文）；
3. 异常统一转友好文本，不向 AI 抛堆栈。

## 五、验证记录（实测）

### 5.1 MCP 协议层

用 `perf/mcp-verify.mjs`（自写的最小 MCP 客户端）验证 **initialize + tools/list + tools/call** 全链路：

```
[1] SSE 已连接，消息端点: /mcp/message?sessionId=5cae357e-...
[2] 握手成功: hmdp-plus-mcp v1.0.0
    protocolVersion: 2024-11-05
[3] tools/list 返回 3 个工具:
    - queryOrderStatus: ...   参数: orderId
    - queryShopVouchers: ...  参数: shopId
    - queryShopById: ...      参数: shopId

[4] tools/call 实际调用工具:
    ✅ queryShopById({"shopId":1})
       {"shopId":1,"name":"103茶餐厅","area":"大关","address":"金华路锦昌文华苑29号",
        "score":3.7,"avgPrice":80,"openHours":"10:00-22:00","sold":4215}
    ✅ queryShopVouchers({"shopId":1})
       [{"voucherId":1,"title":"80元代金券","subTitle":"周一至周日均可使用",
         "payValue":20,"actualValue":100,"type":"秒杀券","status":"上架","stock":200,...}]

✅ MCP Server 完整验证通过（list 3 个工具，call 成功 2 个）
```

**第三个工具的错误路径**（不存在的订单）：

```http
GET /ai/tool/order?orderId=999999
→ HTTP 200，"未找到ID为 999999 的订单"
```

说明工具内部做了异常兜底：**不会向 AI 抛堆栈，而是返回可读的业务提示**。

### 5.2 工具返回数据正确性

```http
GET /ai/tool/shop?id=1
→ {"shopId":1,"name":"103茶餐厅","area":"大关","address":"金华路锦昌文华苑29号",
   "score":3.7,"avgPrice":80,"openHours":"10:00-22:00","sold":4215}

GET /ai/tool/vouchers?shopId=1
→ [{"voucherId":1,"title":"80元代金券","subTitle":"周一至周日均可使用",
    "payValue":20,"actualValue":100,"type":"秒杀券","status":"上架",
    "stock":200,"beginTime":"2026-09-30 13:00:00","endTime":"2026-10-30 15:59:59","minLevel":1}]
```

### 5.3 智能客服（LLM 链路）

未配置有效 key 时：

```json
{"success":false,"errorMsg":"智能客服暂时不可用：HTTP 401 - Authentication Fails, Your api key: ****-key is invalid"}
```

**这条"失败"恰恰证明链路是通的**：ChatClient 创建成功 → 请求真实打到了 DeepSeek → 只是 key 无效。

配置有效 key 后的验证结果见下方"待补"。

## 六、怎么用

### 6.1 配置 DeepSeek Key

**方式 A：环境变量（推荐）**
```
DEEPSEEK_API_KEY=sk-你的key
```
IDEA → Run Configuration → Environment variables 里加。

**方式 B：直接改 application.yml**
```yaml
spring:
  ai:
    deepseek:
      api-key: sk-你的key
```

### 6.2 智能客服对话

```bash
# 第一轮
curl -X POST "http://localhost:8085/ai/chat?message=帮我查一下1号商铺有什么券&sessionId=u1" \
     -H "authorization: <你的登录token>"

# 第二轮（同一 sessionId，能引用上一轮上下文）
curl -X POST "http://localhost:8085/ai/chat?message=那张券还剩多少张？&sessionId=u1" \
     -H "authorization: <你的登录token>"
```

### 6.3 接入外部 MCP 客户端

以 Claude Desktop 为例（`claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "hmdp-plus": {
      "url": "http://localhost:8085/sse"
    }
  }
}
```

配置后即可在 Claude 里直接问"1 号商铺有什么券"，由 Claude 调用本项目的 MCP 工具取数。

## 七、代码位置

| 文件 | 职责 |
|---|---|
| `org/javaup/mcp/McpShopTools.java` | 3 个 `@Tool` 工具实现 |
| `org/javaup/mcp/McpServerConfig.java` | 注册 `ToolCallbackProvider` |
| `org/javaup/mcp/AiChatConfig.java` | `ChatClient` + `ChatMemory`（20 条窗口记忆） |
| `org/javaup/controller/AiChatController.java` | `/ai/chat` + 调试接口 |
| `org/javaup/config/MvcConfig.java` | 放行 `/sse`、`/mcp/**` |
| `perf/mcp-verify.mjs` | 最小 MCP 客户端（验证脚本） |

## 八、工程细节（面试可讲）

1. **`-parameters` 编译选项**：项目父 pom 用的是 `spring-boot-dependencies`（BOM）而非 `spring-boot-starter-parent`，**不会自动开启参数名保留**，导致 MCP 工具参数显示为 `arg0`。在根 pom 的 `maven-compiler-plugin` 加 `<parameters>true</parameters>` 并 `clean` 重编后修复为 `shopId`/`orderId`。
2. **拦截器放行**：新端点默认会被 `LoginInterceptor` 拦成 401，必须显式加入排除列表。
3. **Maven HTTP 仓库被拦**：全局 settings.xml 的阿里云镜像是 `http://`，被 Maven 3.9 的 `http-blocker` 拦截，无法下载新依赖。做法是生成 `perf/mcp-settings.xml`（HTTPS 版）单独使用，**不改用户全局配置**。
4. **工具与客服复用**：同一批 `@Tool` 方法既注册给 MCP Server，也通过 `defaultTools()` 交给 LLM，避免两套实现漂移。
5. **【架构认知】工具执行在 ChatModel 层，不在 ChatClient —— 这个坑我踩得很深，值得讲**

   **最终结论（有字节码证据）**：Spring AI 的工具调用分为三层：

   | 层 | 职责 | 证据 |
   |---|---|---|
   | `ChatClient` | 组装 Prompt（system/user/advisor/**工具定义**） | `DefaultChatClient` 只有 `defaultChatClientRequest` 一个字段，**无 ToolCallingManager**；`ChatOptions` 接口**没有任何 Tool 相关方法** |
   | `ChatModel`（如 `DeepSeekChatModel`） | 调 API + **执行工具循环** | `private final ToolCallingManager toolCallingManager`；`public ChatResponse internalCall(Prompt, ChatResponse)`；`DEFAULT_TOOL_CALLING_MANAGER` |
   | `ToolCallingManager` | 具体执行工具并回填结果 | `DefaultToolCallingManager` |

   **推论（重要）**：**用 Mock 的 ChatModel 测试 Function Calling 是无效的**——你手写的假模型没有 `internalCall` 的工具循环，永远不会执行工具。我为此写了三版测试（手动 `ChatClient.builder`、`defaultToolCallbacks`、`ApplicationContextRunner` 加载自动配置）才发现：**三种方式下模型都只被调用 1 次**，因为问题根本不在 ChatClient 的构造方式。

   **对生产代码的影响**：无。项目用的是真实的 `DeepSeekChatModel`，工具循环由它内部完成。`AiChatConfig` 无论手写 builder 还是注入 Builder 都能工作（注入 Builder 是官方推荐写法，保留）。

   **验证方式**：工具执行闭环**只能用真实模型**通过 `POST /ai/chat` 验证。Mock 测试只能验证到"工具被正确注册为 ToolCallback"这一层（见 `ToolCallbackDiagnoseTest`）。

   **面试价值**：这条比"我会用 ChatClient"有深度——它说明你读过框架分层、用字节码验证过行为，而不是背文档。

## 九、实测通过记录（2026-10-22）

**单轮对话**（真实 DeepSeek，经 OpenAI 兼容协议）：

```
用户：帮我查一下1号商铺有什么优惠券
AI：  1号商铺目前有 1 张优惠券：
     - 80元代金券（秒杀券）
       - 售价：20元，面额：100元
       - 状态：上架，剩余库存：200
       - 活动时间：2026-09-30 13:00:00 至 2026-10-30 15:59:59
```

**数据全部来自 `queryShopVouchers(shopId=1)` 工具返回**（库存 200、活动时间与数据库一致）。

**多轮对话**（同一 sessionId）：

```
第 1 轮：1号商铺叫什么名字
AI：    1号商铺是「103茶餐厅」，位于大关（金华路锦昌文华苑29号），评分3.7，人均80元，
        营业时间10:00-22:00，已售4215份。

第 2 轮：它的人均消费是多少          ← 只用代词"它"
AI：    1号商铺「103茶餐厅」的人均消费是80元。   ← 正确承接上下文
```

## 十、打通 DeepSeek 踩的 5 个坑（面试高价值素材）

从"HTTP 401"到"对话成功"，一共撞了 5 次，每次错误信息都不同：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `401 api key is invalid` | 配置里是占位符 | 填入真实 Key |
| 2 | `422 frequency_penalty: invalid type: string "", expected f32` | ① 项目 `JsonCustomSerializer` 把 **Number 类型的 null** 序列化成 `""`；② `JacksonCustom` 硬编码开启 `WRITE_NUMBERS_AS_STRINGS`，非 null 数字也被转成字符串 | ① 给 `frequency-penalty`/`presence-penalty` 显式赋 0；② 注释掉 `WRITE_NUMBERS_AS_STRINGS` |
| 3 | `422 top_logprobs: invalid type: string "", expected u8` | 同类：Integer 的 null → `""` | 把 `JsonCustomSerializer` 中 Number 分支的 null 序列化改为 `writeNull()` |
| 4 | `422 reasoning_effort: unknown variant ``, expected one of ...` | 同类：**String 的 null → `""`**，而该字段是枚举 | 配 `reasoning-effort: none` |
| 5 | `400 Invalid 'messages[2].tool_calls': empty array. Expected minimum length 1` | 同类：**List 的 null → `[]`** | 把 `JsonCustomSerializer` 中 List/Array 分支改为 `writeNull()`；并过滤 ChatMemory 中带 tool_calls 的工具消息 |

**贯穿始终的元凶**：项目自研的 `JsonCustomSerializer`（一个 `BeanSerializerModifier`）给所有 null 字段塞了"占位值"——String→`""`、Number→`""`、Boolean→`false`、List→`[]`。这对前端友好，但**对第三方 API 请求体是灾难**：数字字段拿到 `""`、数组字段拿到 `[]`，全部违反 API schema。

**这也解释了一件事**：为什么改 yml 里的 `WRITE_NUMBERS_AS_STRINGS: false` 没用——`JacksonCustom.java:84` 在代码里 `featuresToEnable` 了它，配置文件改不动。

**排查方法**（可复用）：每次错误只暴露出**一个字段**，就逐个字段配合法值 + 逐个类型改 null 序列化策略，用 5 轮迭代把问题逼完。比读源码猜测快。

## 十一、副作用与注意事项

关闭 `WRITE_NUMBERS_AS_STRINGS` 后，**所有 Long 型 ID 从字符串变回数字**：

```json
之前：{"id":"1","name":"美食"}
现在：{"id":1,"name":"美食"}
```

- 雪花 ID 是 64 位，JS `Number` 只有 53 位精度，理论上会丢精度；
- **但前端 `request.js` 用了 `json-bigint`（`storeAsString: true`）**，会把超出安全范围的大数自动转成字符串，所以前端不受影响；
- 若有其它客户端（脚本、第三方）依赖"ID 是字符串"，需要相应调整。

## 十二、待补

- [ ] 配置有效 DeepSeek Key 后，补一轮真实多轮对话截图/记录（含工具调用链）
- [ ] 可选：把 `ChatMemory` 从内存换成 Redis 版，支持多实例
