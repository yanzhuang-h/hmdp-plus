# AI 功能自验手册

> 用途：填好 DeepSeek Key 并重启后，按本手册 4 步验证完，即可确认 MCP + 智能客服全部可用。
> 前置：应用启动在 `8085`，已登录拿到 token（`POST /user/code` → `POST /user/login`）。

---

## 第 0 步：填 Key 并重启

```yaml
# hmdp-core-service/src/main/resources/application.yml 第 17 行
deepseek:
  api-key: sk-你的真实key
```

或设环境变量 `DEEPSEEK_API_KEY`。改完**重启应用**。

启动成功的标志（日志里应有）：
- `Tomcat started on port 8085`
- 无 `BeanCreationException`

---

## 第 1 步：MCP 协议（不需要 Key）

```powershell
cd D:\Java\hmdp-plus
node perf/mcp-verify.mjs http://localhost:8085
```

**期望输出**：

```
[1] SSE 已连接，消息端点: /mcp/message?sessionId=xxx
[2] 握手成功: hmdp-plus-mcp v1.0.0
    protocolVersion: 2024-11-05
[3] tools/list 返回 3 个工具:
    - queryOrderStatus ... 参数: orderId
    - queryShopVouchers ... 参数: shopId
    - queryShopById ... 参数: shopId

[4] tools/call 实际调用工具:
    ✅ queryShopById({"shopId":1})
       {"shopId":1,"name":"103茶餐厅",...}
    ✅ queryShopVouchers({"shopId":1})
       [{"voucherId":1,"title":"80元代金券","stock":200,...}]

✅ MCP Server 完整验证通过（list 3 个工具，call 成功 2 个）
```

**判定**：看到 `✅ MCP Server 完整验证通过` 即通过。

---

## 第 2 步：工具直调（不需要 Key，验证工具实现）

```powershell
$token = "登录后拿到的 token"
curl.exe -H "authorization: $token" "http://localhost:8085/ai/tool/shop?id=1"
curl.exe -H "authorization: $token" "http://localhost:8085/ai/tool/vouchers?shopId=1"
curl.exe -H "authorization: $token" "http://localhost:8085/ai/tool/order?orderId=999999"
```

**期望**：
- 前两个返回店铺/券的 JSON（含真实名称、价格、库存）
- 第三个返回 `"未找到ID为 999999 的订单"`（不是 500 报错）

---

## 第 3 步：单轮对话（需要 Key，验证 LLM + 工具调用）

```powershell
$token = "登录后拿到的 token"
curl.exe -X POST -H "authorization: $token" `
  "http://localhost:8085/ai/chat?message=%E5%B8%AE%E6%88%91%E6%9F%A51%E5%8F%B7%E5%95%86%E9%93%BA&sessionId=verify-1"
```

（`message` 是 URL 编码后的"帮我查1号商铺"）

**期望**：返回的 `data` 是一段**提到 103茶餐厅**的自然语言，例如：

```json
{"success":true,"data":"1号商铺是103茶餐厅，位于大关..."}
```

**判定关键**：答案里必须出现**只有查库才知道的信息**（店名、评分 3.7、人均 80、销量 4215）。如果 LLM 编造了别的店名，说明**工具没被调用**。

**反向验证工具调用**：去应用日志里搜 `[MCP]` 或 `queryShopById`，应能看到工具执行记录。

---

## 第 4 步：多轮对话（需要 Key，验证记忆）

```powershell
# 第 2 轮，必须用同一个 sessionId=verify-1
$msg2 = [uri]::EscapeDataString("那家店有什么券？")
curl.exe -X POST -H "authorization: $token" `
  "http://localhost:8085/ai/chat?message=$msg2&sessionId=verify-1"
```

**期望**：第二轮的答案能**承接上一轮**（知道"那家店"指 103茶餐厅），并调用 `queryShopVouchers`。

**判定**：如果第二轮回答说"您指的是哪家店？"，说明记忆没生效。

---

## 常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `/ai/chat` 返回 `HTTP 401 - api key is invalid` | Key 无效/未生效 | 检查 yml 或环境变量；确认重启 |
| `/ai/chat` 返回 `HTTP 402 - Insufficient Balance` | DeepSeek 余额不足 | 充值 |
| 答案里**没有**真实店铺数据、明显是编的 | 工具没被调用 | ① 确认 key 有效；② 看日志有没有工具执行记录；③ 检查 `application.yml` 的 `spring.ai.mcp.server.capabilities.tool=true` |
| `/sse` 返回 401 | 拦截器没放行 | 确认 `MvcConfig` 里有 `"/sse"`、`"/mcp/**"` |
| 工具参数显示 `arg0` | 编译没开 `-parameters` | 根 pom 的 `maven-compiler-plugin` 加 `<parameters>true</parameters>` 后 `mvn clean install` |
| 第二轮忘记上下文 | sessionId 不一致 | 两轮必须传**同一个** `sessionId` |

---

## 验收清单

全部打勾即功能完整：

- [ ] `perf/mcp-verify.mjs` 输出 `✅ MCP Server 完整验证通过`
- [ ] `/ai/tool/shop?id=1` 返回 103茶餐厅
- [ ] `/ai/chat` 答案里出现真实店铺数据（非编造）
- [ ] 日志里能看到工具被执行
- [ ] 同 sessionId 第二轮能承接上下文

---

## 已完成、无需重复验证的部分

以下已由自动化测试覆盖（`mvn test -Dtest='ChatMemoryTest,ToolCallbackDiagnoseTest'`）：

- ✅ 3 个 `@Tool` 被正确解析为 ToolCallback，参数名为 `shopId`/`orderId`（不是 `arg0`）
- ✅ 每个工具都有描述
- ✅ ChatMemory 按 sessionId 隔离
- ✅ 同一会话内消息按序累积
- ✅ 超过 20 条窗口上限时裁剪最旧消息
