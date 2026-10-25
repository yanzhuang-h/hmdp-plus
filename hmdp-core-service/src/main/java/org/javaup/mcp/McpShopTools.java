package org.javaup.mcp;

import cn.hutool.json.JSONUtil;
import io.micrometer.tracing.Span;
import org.apache.logging.log4j.ThreadContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.mcp.rag.RuleRetriever;
import org.javaup.mcp.rag.RuleRetriever.ScoredRule;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * MCP 工具集：把平台的商户/优惠券/订单查询能力，以标准 MCP 工具的形式暴露给 AI 客户端。
 *
 * <p>设计约束：
 * <ul>
 *   <li>MCP 调用没有登录上下文，因此不能依赖 {@code UserHolder}；</li>
 *   <li>工具只做只读查询，不做写操作，避免 AI 误改业务数据；</li>
 *   <li>返回 JSON 字符串，便于大模型解析；异常统一转为友好文本，不抛堆栈。</li>
 * </ul>
 *
 * <p><b>稳定性保障</b>（工具层）：
 * <ul>
 *   <li><b>超时控制</b>：每个工具在独立线程池执行，超过 {@code mcp.tool.timeout-ms}
 *       即中断等待并返回降级文案，避免慢查询把大模型请求长时间挂起；</li>
 *   <li><b>降级兜底</b>：超时、线程池拒绝、业务异常一律转换为可读文本，
 *       让对话可以继续，而不是整个 /ai/chat 请求失败；</li>
 *   <li><b>耗时日志</b>：每次工具调用记录耗时，便于定位慢工具。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpShopTools {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    /**
     * 工具专用线程池：与 Tomcat 请求线程隔离，避免工具阻塞拖垮 Web 容器。
     * 队列满时直接拒绝（AbortPolicy），由 {@link #guard} 捕获并降级。
     */
    private static final ExecutorService TOOL_EXECUTOR = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "mcp-tool-" + POOL_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private final IShopService shopService;
    private final IVoucherService voucherService;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;
    private final Tracer tracer;
    private final RuleRetriever ruleRetriever;

    /** 单个工具的超时时间（毫秒），可通过 mcp.tool.timeout-ms 覆盖 */
    @Value("${mcp.tool.timeout-ms:2000}")
    private long timeoutMs;

    // ==================================================================
    // 工具定义（对外签名不变，内部统一走 guard 包装）
    // ==================================================================

    @Tool(description = "根据商铺ID查询商铺详情，包括名称、商圈、地址、评分、人均消费、营业时间、销量")
    public String queryShopById(@ToolParam(description = "商铺ID，例如 1") Long shopId) {
        return guard("商铺查询", () -> doQueryShopById(shopId));
    }

    @Tool(description = "根据商铺ID查询该商铺下的全部优惠券，包含面额、售价、状态；若为秒杀券还会返回剩余库存与活动时间")
    public String queryShopVouchers(@ToolParam(description = "商铺ID，例如 1") Long shopId) {
        return guard("优惠券查询", () -> doQueryShopVouchers(shopId));
    }

    /**
     * 查询当前登录用户的订单状态。
     *
     * <p><b>越权防护</b>：用户身份由服务端通过 {@link ToolContext} 注入（应用内对话取自登录态），
     * <b>不接受模型传入的 userId</b>。MCP 外部调用没有身份上下文，因此直接拒绝——
     * 订单属于用户私有数据，不通过 MCP 暴露。
     *
     * <p>订单不属于当前用户时统一返回「未找到」，不暴露订单是否存在。
     */
    @Tool(description = "查询当前登录用户本人的优惠券订单状态。只能查询自己的订单，不能查询他人的订单。")
    public String queryOrderStatus(
            @ToolParam(description = "订单ID（雪花ID，例如 2112869162882732034）") Long orderId,
            ToolContext toolContext) {
        return guard("订单查询", () -> doQueryOrderStatus(orderId, toolContext));
    }

    /**
     * 查询平台规则（RAG 的生成入口）。
     *
     * <p>先由 {@link RuleRetriever} 检索相关规则片段，再把<b>规则原文</b>返回给模型，
     * 让模型基于规则回答，而不是靠自身知识编造。
     *
     * <p>检索不到时<b>明确返回「未找到」</b>并提示不要编造——这是抑制幻觉的关键：
     * 如果这里返回空字符串或模糊结果，模型会自己"圆"一个答案。
     */
    @Tool(description = "查询平台规则与政策，例如优惠券使用规则、秒杀与限购规则、订单异常处理、"
            + "退款规则、订阅提醒、缓存与数据一致性。当用户询问「为什么」「能不能」「怎么处理」"
            + "这类政策性问题时使用本工具，而不要凭空回答。")
    public String queryPlatformRule(
            @ToolParam(description = "用户问题的核心关键词，例如「券能不能退」「抢不到订单」「一个人能抢几张」")
            String question) {
        return guard("规则查询", () -> doQueryPlatformRule(question));
    }

    private String doQueryPlatformRule(String question) {
        try {
            List<ScoredRule> results = ruleRetriever.search(question);
            if (results.isEmpty()) {
                log.info("[RAG] 未命中 question={}", question);
                return "未查询到与该问题相关的平台规则。请如实告知用户「暂无相关规则」，不要自行推测或编造。";
            }
            List<Map<String, Object>> list = new ArrayList<>(results.size());
            for (ScoredRule r : results) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", r.chunk().title());
                m.put("content", r.chunk().content());
                m.put("source", r.chunk().source());
                list.add(m);
            }
            log.info("[RAG] 命中 {} 条 question={} top={} score={}",
                    list.size(), question, results.get(0).chunk().title(),
                    String.format("%.2f", results.get(0).score()));
            return JSONUtil.toJsonStr(list);
        } catch (Exception e) {
            log.warn("[MCP] queryPlatformRule 失败 question={}", question, e);
            return "规则查询失败：" + e.getMessage();
        }
    }

    // ==================================================================
    // 工具真实实现
    // ==================================================================

    private String doQueryShopById(Long shopId) {
        try {
            Shop shop = shopService.getById(shopId);
            if (shop == null) {
                return "未找到ID为 " + shopId + " 的商铺";
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("shopId", shop.getId());
            r.put("name", shop.getName());
            r.put("area", shop.getArea());
            r.put("address", shop.getAddress());
            // 数据库里评分是乘 10 保存的整数，这里还原成 1~5 分
            r.put("score", shop.getScore() == null ? null : shop.getScore() / 10.0);
            r.put("avgPrice", shop.getAvgPrice());
            r.put("openHours", shop.getOpenHours());
            r.put("sold", shop.getSold());
            return JSONUtil.toJsonStr(r);
        } catch (Exception e) {
            log.warn("[MCP] queryShopById 失败 shopId={}", shopId, e);
            return "查询商铺失败：" + e.getMessage();
        }
    }

    private String doQueryShopVouchers(Long shopId) {
        try {
            List<Voucher> vouchers = voucherService.lambdaQuery()
                    .eq(Voucher::getShopId, shopId)
                    .list();
            if (vouchers == null || vouchers.isEmpty()) {
                return "商铺 " + shopId + " 当前没有优惠券";
            }
            List<Map<String, Object>> list = new ArrayList<>(vouchers.size());
            for (Voucher v : vouchers) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("voucherId", v.getId());
                m.put("title", v.getTitle());
                m.put("subTitle", v.getSubTitle());
                m.put("payValue", v.getPayValue());
                m.put("actualValue", v.getActualValue());
                m.put("type", v.getType() != null && v.getType() == 1 ? "秒杀券" : "普通券");
                m.put("status", statusText(v.getStatus()));
                // 秒杀券补充库存和活动时间
                if (v.getType() != null && v.getType() == 1) {
                    SeckillVoucherFullModel sv = seckillVoucherService.queryByVoucherId(v.getId());
                    if (sv != null) {
                        m.put("stock", sv.getInitStock());
                        m.put("beginTime", format(sv.getBeginTime()));
                        m.put("endTime", format(sv.getEndTime()));
                        m.put("minLevel", sv.getMinLevel());
                    }
                }
                list.add(m);
            }
            return JSONUtil.toJsonStr(list);
        } catch (Exception e) {
            log.warn("[MCP] queryShopVouchers 失败 shopId={}", shopId, e);
            return "查询优惠券失败：" + e.getMessage();
        }
    }

    private String doQueryOrderStatus(Long orderId, ToolContext toolContext) {
        try {
            Object uid = toolContext == null ? null : toolContext.getContext().get("userId");
            if (!(uid instanceof Long userId)) {
                log.warn("[MCP] queryOrderStatus 拒绝：无用户身份上下文 orderId={}", orderId);
                return "无法确认当前用户身份。订单状态查询仅支持已登录的应用内对话；"
                        + "外部 MCP 客户端不提供订单数据。";
            }
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                return "未找到ID为 " + orderId + " 的订单";
            }
            if (!userId.equals(order.getUserId())) {
                // 越权尝试：不泄露订单是否存在
                log.warn("[MCP] queryOrderStatus 越权拦截 userId={} 尝试查询 orderId={}", userId, orderId);
                return "未找到ID为 " + orderId + " 的订单";
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("orderId", order.getId());
            r.put("voucherId", order.getVoucherId());
            r.put("status", order.getStatus() != null && order.getStatus() == 1 ? "正常" : "已取消");
            r.put("reconciliationStatus", reconciliationText(order.getReconciliationStatus()));
            r.put("createTime", format(order.getCreateTime()));
            r.put("payTime", format(order.getPayTime()));
            r.put("useTime", format(order.getUseTime()));
            r.put("refundTime", format(order.getRefundTime()));
            return JSONUtil.toJsonStr(r);
        } catch (Exception e) {
            log.warn("[MCP] queryOrderStatus 失败 orderId={}", orderId, e);
            return "查询订单失败：" + e.getMessage();
        }
    }

    // ==================================================================
    // 超时与降级守卫
    // ==================================================================

    /**
     * 在独立线程池执行工具逻辑，超过 {@code timeoutMs} 返回降级文案。
     *
     * <p>注意：Java 无法真正中断正在阻塞的数据库调用，因此这里的“超时”是
     * <b>不再等待结果</b>（放弃等待 + 快速返回），线程会自行结束。
     * 这是工具层超时控制的常规做法：优先保证对话链路可用。
     */
    private String guard(String toolName, Supplier<String> action) {
        long start = System.currentTimeMillis();
        // 链路埋点：工具调用作为子 span，自动挂在 ai.chat 的 span 下面
        Span span = tracer.nextSpan().name("mcp.tool").tag("tool.name", toolName).start();
        final String traceId = span.context().traceId();
        final String spanId = span.context().spanId();
        // 父线程（Tomcat 请求线程）也要绑定：guard 内的收尾日志（[MCP] 完成）打在这里
        ThreadContext.put("traceId", traceId);
        ThreadContext.put("spanId", spanId);

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            try {
                // 关键：ThreadContext 是 ThreadLocal，工具实际跑在线程池的子线程里。
                // 必须在子线程内部绑定，否则子线程的日志取不到父线程的 traceId。
                String result = CompletableFuture
                        .supplyAsync(() -> {
                            ThreadContext.put("traceId", traceId);
                            ThreadContext.put("spanId", spanId);
                            try {
                                return action.get();
                            } finally {
                                ThreadContext.remove("traceId");
                                ThreadContext.remove("spanId");
                            }
                        }, TOOL_EXECUTOR)
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .join();
                long cost = System.currentTimeMillis() - start;
                span.tag("tool.result", "success");
                log.info("[MCP] {} 完成 耗时={}ms", toolName, cost);
                return result;
            } catch (CompletionException e) {
                long cost = System.currentTimeMillis() - start;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof TimeoutException) {
                    span.tag("tool.result", "timeout");
                    log.warn("[MCP] {} 超时 耗时={}ms 阈值={}ms 触发降级", toolName, cost, timeoutMs);
                    return "查询超时（超过 " + timeoutMs + " 毫秒），已降级处理，请稍后重试或换个问法。";
                }
                span.tag("tool.result", "error");
                span.error(cause);
                log.warn("[MCP] {} 执行异常 耗时={}ms 触发降级: {}", toolName, cost, cause.toString());
                return "查询暂时不可用，已降级处理，请稍后重试。";
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - start;
                // 线程池拒绝（AbortPolicy）会落到这里
                span.tag("tool.result", "rejected");
                span.error(e);
                log.warn("[MCP] {} 提交失败 耗时={}ms 触发降级: {}", toolName, cost, e.toString());
                return "查询繁忙，已降级处理，请稍后重试。";
            }
        } finally {
            span.tag("tool.cost.ms", String.valueOf(System.currentTimeMillis() - start));
            span.end();
            clearTraceFromMdc();
        }
    }

    /**
     * 把当前 span 的 traceId / spanId 写入 Log4j2 的 ThreadContext，
     * 使 log4j2.xml 的 {@code %X{traceId}} 能取到值。
     *
     * <p>为什么不用 brave 的 MDCScopeDecorator：它依赖 Spring Boot 装配
     * ScopeDecorator，而当前版本的自动配置并不会消费自定义的 ScopeDecorator Bean。
     * 手动写入更直接、行为可预期。
     */
    private void bindTraceToMdc(Span span) {
        if (span == null) {
            log.warn("[TRACE] span 为 null");
            return;
        }
        if (span.context() == null) {
            log.warn("[TRACE] span.context() 为 null");
            return;
        }
        String traceId = span.context().traceId();
        String spanId = span.context().spanId();
        // 诊断日志：直接打印取到的值，判断是 Tracer 为 NOOP 还是 MDC 写入失败
        log.info("[TRACE] tracer={} span={} traceId={} spanId={}",
                tracer.getClass().getSimpleName(),
                span.getClass().getSimpleName(),
                traceId == null || traceId.isEmpty() ? "<EMPTY>" : traceId,
                spanId == null || spanId.isEmpty() ? "<EMPTY>" : spanId);
        ThreadContext.put("traceId", traceId);
        ThreadContext.put("spanId", spanId);
    }

    /** 清理 MDC，避免线程复用时串号 */
    private void clearTraceFromMdc() {
        ThreadContext.remove("traceId");
        ThreadContext.remove("spanId");
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private String format(java.time.LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    private String statusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 1 -> "上架";
            case 2 -> "下架";
            case 3 -> "过期";
            default -> "未知(" + status + ")";
        };
    }

    private String reconciliationText(Integer s) {
        if (s == null) return "未知";
        return switch (s) {
            case 1 -> "待处理";
            case 2 -> "异常";
            case 3 -> "不一致";
            case 4 -> "一致";
            default -> "未知(" + s + ")";
        };
    }
}
