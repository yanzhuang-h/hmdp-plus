package org.javaup.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.javaup.mcp.rag.RuleRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MCP 工具注册的回归测试。
 *
 * <p>锁定三件事，防止后续改动悄悄破坏工具契约：
 * <ol>
 *   <li><b>工具数量</b>：当前 4 个（商铺 / 优惠券 / 订单 / 平台规则）</li>
 *   <li><b>参数名</b>：必须是真实名字（依赖编译期 {@code -parameters}），不能退化成 {@code arg0}</li>
 *   <li><b>工具描述</b>：每个工具都要有，模型靠它决定何时调用</li>
 * </ol>
 *
 * <p><b>关于工具执行</b>：Mock 模型下无法完整验证"模型请求工具 → 框架执行 → 结果回填"
 * 的闭环（工具执行循环在 ChatModel 实现层的 ToolCallingManager 里，见
 * docs/MCP与智能客服.md 第 8 节）。该闭环需用真实 LLM 通过 {@code POST /ai/chat} 验证。
 */
class ToolCallbackDiagnoseTest {

    private McpShopTools buildTools() {
        // 规则检索器需要真实加载，否则 queryPlatformRule 的 schema 虽在、调用会空指针
        RuleRetriever retriever = new RuleRetriever();
        retriever.load();
        return new McpShopTools(
                mock(org.javaup.service.IShopService.class),
                mock(org.javaup.service.IVoucherService.class),
                mock(org.javaup.service.ISeckillVoucherService.class),
                mock(org.javaup.service.IVoucherOrderService.class),
                // 工具层已接入链路埋点，单测里用 NOOP Tracer，不产生真实 span
                io.micrometer.tracing.Tracer.NOOP,
                retriever,
                mock(org.javaup.mcp.orchestration.OrderDiagnosisOrchestrator.class));
    }

    @Test
    @DisplayName("@Tool 应解析出 5 个工具，且参数名/描述正确")
    void shouldExposeFiveToolsWithCorrectSchema() {
        McpShopTools tools = buildTools();
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();

        assertEquals(5, callbacks.length, "应解析出 5 个 MCP 工具");

        var names = java.util.Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .sorted()
                .toList();
        assertEquals(
                java.util.List.of("diagnoseOrderIssue", "queryOrderStatus", "queryPlatformRule",
                        "queryShopById", "queryShopVouchers"),
                names);

        for (ToolCallback cb : callbacks) {
            String schema = cb.getToolDefinition().inputSchema();
            String name = cb.getToolDefinition().name();
            assertTrue(cb.getToolDefinition().description() != null
                            && !cb.getToolDefinition().description().isBlank(),
                    name + " 应有工具描述");

            // 参数名必须是真实名字（依赖编译期 -parameters），不能是 arg0
            String expectedParam = switch (name) {
                case "queryOrderStatus" -> "orderId";
                case "queryPlatformRule" -> "question";
                case "diagnoseOrderIssue" -> "orderId";
                default -> "shopId";
            };
            assertTrue(schema.contains("\"" + expectedParam + "\""),
                    name + " 的参数名应为 " + expectedParam + "，实际 schema: " + schema);
            assertTrue(!schema.contains("arg0"),
                    name + " 不应出现 arg0（检查 maven-compiler-plugin 的 -parameters）");

            System.out.println("[工具注册] " + name + " 参数=" + expectedParam + " Schema 校验通过");
        }
    }
}
