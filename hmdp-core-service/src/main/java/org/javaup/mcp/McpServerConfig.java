package org.javaup.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 配置。
 *
 * <p>把 {@link McpShopTools} 中以 {@code @Tool} 标注的方法注册为 MCP 工具，
 * Spring AI 的 MCP Server 自动配置会读取 {@link ToolCallbackProvider} 并以
 * 标准 MCP 协议（JSON-RPC over SSE）对外暴露，供 Claude Desktop、Cursor
 * 等任意 MCP 客户端调用。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpShopToolCallbackProvider(McpShopTools mcpShopTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(mcpShopTools)
                .build();
    }
}
