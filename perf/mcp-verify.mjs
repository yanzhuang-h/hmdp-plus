#!/usr/bin/env node
/**
 * 最小 MCP 客户端：验证 MCP Server 是否按标准协议暴露工具。
 *
 * 流程：
 *   1. GET /sse 建立 SSE 流，从 event: endpoint 拿到消息端点（含 sessionId）
 *   2. POST initialize 握手
 *   3. POST tools/list 拉取工具清单
 *   4. 打印工具名称与描述
 *
 * 用法：node perf/mcp-verify.mjs [baseUrl]
 */

const BASE = process.argv[2] || 'http://localhost:8086';

function createSseReader() {
  const pending = [];
  const waiters = [];
  let endpoint;

  const handleEvent = (raw) => {
    // SSE 块形如：event: message\ndata: {...}
    const lines = raw.split('\n');
    let event = 'message';
    const dataLines = [];
    for (const line of lines) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    const data = dataLines.join('\n');
    if (!data) return;

    if (event === 'endpoint') {
      endpoint = data;
      return;
    }
    // 普通消息：JSON-RPC 响应
    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }
    const w = waiters.shift();
    if (w) w.resolve(msg);
    else pending.push(msg);
  };

  const nextMessage = (timeoutMs = 15000) =>
    new Promise((resolve, reject) => {
      if (pending.length) return resolve(pending.shift());
      const timer = setTimeout(() => reject(new Error('等待 MCP 响应超时')), timeoutMs);
      waiters.push({
        resolve: (m) => {
          clearTimeout(timer);
          resolve(m);
        },
      });
    });

  return {
    getEndpoint: () => endpoint,
    nextMessage,
    start: async () => {
      const res = await fetch(`${BASE}/sse`, {
        headers: { Accept: 'text/event-stream' },
      });
      if (!res.ok) throw new Error(`/sse 返回 ${res.status}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      (async () => {
        try {
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            let idx;
            while ((idx = buffer.indexOf('\n\n')) >= 0) {
              const raw = buffer.slice(0, idx);
              buffer = buffer.slice(idx + 2);
              handleEvent(raw);
            }
          }
        } catch {
          /* 连接关闭 */
        }
      })();
    },
  };
}

async function main() {
  const sse = createSseReader();
  await sse.start();

  // 等待 endpoint 事件
  for (let i = 0; i < 50 && !sse.getEndpoint(); i++) {
    await new Promise((r) => setTimeout(r, 100));
  }
  const endpoint = sse.getEndpoint();
  if (!endpoint) throw new Error('未收到 endpoint 事件，MCP Server 可能未启用');
  console.log(`[1] SSE 已连接，消息端点: ${endpoint}`);

  const post = async (payload) => {
    const url = new URL(endpoint, BASE).toString();
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) throw new Error(`POST ${endpoint} 返回 ${res.status}`);
    return sse.nextMessage();
  };

  // initialize
  const init = await post({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'hmdp-verify', version: '1.0.0' },
    },
  });
  if (init.error) throw new Error(`initialize 失败: ${JSON.stringify(init.error)}`);
  const serverInfo = init.result?.serverInfo || {};
  console.log(`[2] 握手成功: ${serverInfo.name || '?'} v${serverInfo.version || '?'}`);
  console.log(`    protocolVersion: ${init.result?.protocolVersion}`);

  // 通知 initialized
  await fetch(new URL(endpoint, BASE), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  }).catch(() => {});

  // tools/list
  const tools = await post({
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/list',
    params: {},
  });
  if (tools.error) throw new Error(`tools/list 失败: ${JSON.stringify(tools.error)}`);

  const list = tools.result?.tools || [];
  console.log(`[3] tools/list 返回 ${list.length} 个工具:`);
  for (const t of list) {
    console.log(`    - ${t.name}: ${(t.description || '').slice(0, 60)}`);
    const props = Object.keys(t.inputSchema?.properties || {});
    if (props.length) console.log(`        参数: ${props.join(', ')}`);
  }

  if (list.length === 0) {
    console.log('\n❌ 未注册任何工具');
    process.exit(1);
  }

  // ===== tools/call：真正调用工具，验证端到端可执行 =====
  console.log('\n[4] tools/call 实际调用工具:');
  const calls = [
    { name: 'queryShopById', arguments: { shopId: 1 } },
    { name: 'queryShopVouchers', arguments: { shopId: 1 } },
  ];
  let callOk = 0;
  for (const c of calls) {
    const resp = await post({
      jsonrpc: '2.0',
      id: 100 + callOk,
      method: 'tools/call',
      params: { name: c.name, arguments: c.arguments },
    });
    if (resp.error) {
      console.log(`    ❌ ${c.name} 调用失败: ${JSON.stringify(resp.error)}`);
      continue;
    }
    const content = resp.result?.content || [];
    const text = content.map((x) => x.text).filter(Boolean).join('\n');
    const isErr = resp.result?.isError === true;
    if (isErr) {
      console.log(`    ⚠️ ${c.name} 返回业务错误: ${text.slice(0, 120)}`);
    } else {
      callOk++;
      console.log(`    ✅ ${c.name}(${JSON.stringify(c.arguments)})`);
      console.log(`       ${text.slice(0, 160)}${text.length > 160 ? '…' : ''}`);
    }
  }

  if (callOk === 0) {
    console.log('\n❌ 没有任何工具调用成功');
    process.exit(1);
  }
  console.log(`\n✅ MCP Server 完整验证通过（list ${list.length} 个工具，call 成功 ${callOk} 个）`);
  process.exit(0);
}

main().catch((e) => {
  console.error('❌ 验证失败:', e.message);
  process.exit(1);
});
