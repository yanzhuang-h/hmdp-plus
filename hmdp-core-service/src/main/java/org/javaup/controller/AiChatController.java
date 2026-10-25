package org.javaup.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.dto.Result;
import org.javaup.dto.UserDTO;
import org.javaup.mcp.McpShopTools;
import org.javaup.utils.UserHolder;
import io.micrometer.tracing.Span;
import org.apache.logging.log4j.ThreadContext;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 智能客服接口。
 *
 * <p>对话入口：前端传入用户问题与会话 ID，由大模型自主决定调用哪个查询工具。
 * 同一 {@code sessionId} 内的多轮对话共享上下文。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final ChatClient shopChatClient;
    private final McpShopTools mcpShopTools;
    private final Tracer tracer;

    /**
     * 多轮对话。
     *
     * @param message   用户问题，例如"帮我查一下 1 号商铺有什么券"
     * @param sessionId 会话标识，同一个值代表同一轮对话
     */
    @PostMapping("/chat")
    public Result<String> chat(@RequestParam("message") String message,
                               @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        // 用户身份由服务端注入（不从模型参数取），用于工具层的越权校验
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("未登录");
        }
        // 链路埋点：为一次对话建立 span，子 span（工具调用）会自动挂到它下面
        Span span = tracer.nextSpan()
                .name("ai.chat")
                .tag("session.id", sessionId)
                .tag("message.length", String.valueOf(message.length()))
                .start();
        // 手动写 Log4j2 ThreadContext，让本次对话的所有日志带上同一个 traceId
        ThreadContext.put("traceId", span.context().traceId());
        ThreadContext.put("spanId", span.context().spanId());
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String answer = shopChatClient.prompt()
                    .user(message)
                    .toolContext(Map.of("userId", currentUser.getId()))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
            span.tag("answer.length", String.valueOf(answer == null ? 0 : answer.length()));
            return Result.ok(answer);
        } catch (Exception e) {
            span.error(e);
            log.error("[AI] 对话失败 sessionId={} message={}", sessionId, message, e);
            return Result.fail("智能客服暂时不可用：" + e.getMessage());
        } finally {
            span.end();
            ThreadContext.remove("traceId");
            ThreadContext.remove("spanId");
        }
    }

    // ===== 以下为调试入口：不经过大模型，直接调用 MCP 工具实现，用于验证工具本身是否正确 =====

    @GetMapping("/tool/shop")
    public Result<String> debugQueryShop(@RequestParam("id") Long id) {
        return Result.ok(mcpShopTools.queryShopById(id));
    }

    @GetMapping("/tool/vouchers")
    public Result<String> debugQueryVouchers(@RequestParam("shopId") Long shopId) {
        return Result.ok(mcpShopTools.queryShopVouchers(shopId));
    }

    @GetMapping("/tool/order")
    public Result<String> debugQueryOrder(@RequestParam("orderId") Long orderId) {
        // 模拟应用内场景：注入当前登录用户身份
        UserDTO u = UserHolder.getUser();
        Map<String, Object> ctx = u == null ? Map.of() : Map.of("userId", u.getId());
        return Result.ok(mcpShopTools.queryOrderStatus(orderId, new ToolContext(ctx)));
    }

    /**
     * 模拟 MCP 外部调用（无用户身份），用于验证「订单数据不通过 MCP 暴露」这条约束。
     */
    @GetMapping("/tool/order/no-auth")
    public Result<String> debugQueryOrderWithoutAuth(@RequestParam("orderId") Long orderId) {
        return Result.ok(mcpShopTools.queryOrderStatus(orderId, new ToolContext(Map.of())));
    }
}
