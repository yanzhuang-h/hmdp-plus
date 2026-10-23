package org.javaup.mcp;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 智能客服配置。
 *
 * <p>把与 MCP Server 同一批工具（{@link McpShopTools}）注册给大模型，
 * 通过 Function Calling 让模型自行决定调用哪个查询工具，配合
 * {@link ChatMemory} 实现多轮对话的上下文记忆。
 *
 * <p><b>关键：必须注入 Spring 自动配置的 {@link ChatClient.Builder}，而不是
 * 自己调用 {@code ChatClient.builder(chatModel)}。</b>
 * 手写构建的 ChatClient 不会执行工具调用（收到 ToolCall 会原样返回、content 为空），
 * 因为工具执行所需的内部装配（ToolCallingManager 等）由自动配置提供。
 *
 * <p>与 MCP 的关系：
 * <ul>
 *   <li>MCP Server：把工具以标准协议暴露给<b>外部</b> AI 客户端（Claude Desktop / Cursor 等）；</li>
 *   <li>本配置：在<b>应用内部</b>用同一批工具实现对话式客服，两者复用同一套工具实现。</li>
 * </ul>
 */
@Configuration
public class AiChatConfig {

    private static final String SYSTEM_PROMPT = """
            你是「黑马点评」本地生活平台的智能客服助手。
            你可以调用工具查询：商铺详情、商铺优惠券、优惠券订单状态。

            回答要求：
            1. 用中文，简洁直接，不要啰嗦；
            2. 所有事实（名称、价格、库存、状态）必须来自工具返回结果，绝不编造；
            3. 工具返回"未找到"时，如实告知并建议用户核对 ID；
            4. 用户问题与平台业务无关时，礼貌说明你只负责平台相关的咨询。
            """;

    /**
     * 对话记忆：按会话 ID 保存最近 20 条消息，实现多轮上下文。
     * 单实例内存实现；多实例部署时可替换为 Redis 版 ChatMemoryRepository。
     */
    @Bean
    public ChatMemory chatMemory() {
        ChatMemory delegate = MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
        return new ChatMemory() {
            @Override
            public void add(String conversationId, List<Message> messages) {
                delegate.add(conversationId, messages);
            }

            /**
             * 读取历史时过滤掉「工具调用轮」的消息：
             * 第一轮的 AssistantMessage 带 tool_calls、随后是 ToolResponseMessage。
             * 这些消息回灌给 DeepSeek 时，tool_calls 会被序列化成空数组，
             * 触发 400 "tool_calls: empty array. Expected an array with minimum length 1"。
             * 过滤后只保留纯文本的 user/assistant 对话，多轮上下文依然有效。
             */
            @Override
            public List<Message> get(String conversationId) {
                return delegate.get(conversationId).stream()
                        .filter(m -> !(m instanceof ToolResponseMessage))
                        .filter(m -> !(m instanceof AssistantMessage am && am.hasToolCalls()))
                        .toList();
            }

            @Override
            public void clear(String conversationId) {
                delegate.clear(conversationId);
            }
        };
    }

    /**
     * 智能客服客户端。
     *
     * @param builder 由 Spring AI 自动配置提供（已装配 ToolCallingManager 等），
     *                不要替换为 ChatClient.builder(chatModel)
     */
    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder,
                                     ChatMemory chatMemory,
                                     McpShopTools mcpShopTools) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(mcpShopTools)
                .build();
    }
}
