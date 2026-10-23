package org.javaup.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 工具注册验证（可重复执行的回归测试）。
 *
 * <p>验证 {@link McpShopTools} 上的 {@code @Tool} 能被 Spring AI 正确解析为
 * {@link ToolCallback}，包括工具名、参数名与 JSON Schema——这是 MCP Server 与智能客服
 * 共同依赖的基础。参数名依赖编译期 {@code -parameters}，因此本测试也能防止该配置被回退。
 *
 * <p><b>关于工具执行</b>：Mock 模型下无法完整验证"模型请求工具 → 框架执行 → 结果回填"
 * 的闭环（ChatClient 内部的工具执行路径依赖真实模型响应与自动配置装配）。
 * 该闭环需用真实 LLM 通过 {@code POST /ai/chat} 验证，见 docs/MCP与智能客服.md。
 */
class ToolCallbackDiagnoseTest {

    private McpShopTools buildTools() {
        return new McpShopTools(
                mock(org.javaup.service.IShopService.class),
                mock(org.javaup.service.IVoucherService.class),
                mock(org.javaup.service.ISeckillVoucherService.class),
                mock(org.javaup.service.IVoucherOrderService.class));
    }

    @Test
    @DisplayName("@Tool 应解析出 3 个工具，且参数名/描述正确")
    void shouldExposeThreeToolsWithCorrectSchema() {
        McpShopTools tools = buildTools();
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertEquals(3, callbacks.length, "应解析出 3 个 MCP 工具");

        var names = java.util.Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .sorted()
                .toList();
        assertEquals(java.util.List.of("queryOrderStatus", "queryShopById", "queryShopVouchers"), names);

        for (ToolCallback cb : callbacks) {
            String schema = cb.getToolDefinition().inputSchema();
            String name = cb.getToolDefinition().name();
            assertTrue(cb.getToolDefinition().description() != null
                            && !cb.getToolDefinition().description().isBlank(),
                    name + " 应有工具描述");

            // 参数名必须是真实名字（依赖编译期 -parameters），不能是 arg0
            String expectedParam = name.equals("queryOrderStatus") ? "orderId" : "shopId";
            assertTrue(schema.contains("\"" + expectedParam + "\""),
                    name + " 的参数名应为 " + expectedParam + "，实际 schema: " + schema);
            assertTrue(!schema.contains("arg0"),
                    name + " 不应出现 arg0（检查 maven-compiler-plugin 的 -parameters）");

            System.out.println("[工具注册] " + name + " 参数=" + expectedParam + " Schema 校验通过");
        }
    }
}
