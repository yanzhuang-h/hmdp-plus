package org.javaup.mcp.orchestration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.entity.VoucherReconcileLog;
import org.javaup.mcp.rag.RuleRetriever;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.service.IVoucherService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
import java.util.function.Supplier;

/**
 * 订单异常诊断编排器 —— 「跨工具任务编排」的工程实现。
 *
 * <h3>为什么不靠模型自由编排</h3>
 * 模型确实能自己连着调几个工具，但那有三个问题：
 * <ol>
 *   <li><b>依赖关系靠模型猜</b>：模型并不知道"要先查订单拿到 voucherId 才能查券"，
 *       它会试错、重复调用，把 token 和用户等待时间都烧掉</li>
 *   <li><b>没有并行</b>：模型是一步一步调的，查订单和查对账日志本可同时进行</li>
 *   <li><b>失败不可控</b>：某个数据源挂了，模型要么整段失败，要么编一个答案</li>
 * </ol>
 * 所以把<b>稳定的多步依赖固化成复合工具</b>：编排逻辑在代码里，可测试、可并行、可降级。
 * 模型只需要调这一个工具，拿到结构化的诊断结论。
 *
 * <h3>编排 DAG</h3>
 * <pre>
 *          ┌─ 查订单 ────┐
 * orderId ─┤             ├─→ 查券 ─→ 查商铺 ─┐
 *          └─ 查对账日志 ─┘                    ├─→ 诊断推理 ─→ 规则检索
 *                                               ┘
 * </pre>
 * 「查订单」与「查对账日志」只依赖 orderId，<b>并行执行</b>；
 * 「查券」依赖订单里的 voucherId；「查商铺」依赖券里的 shopId；
 * 「规则检索」依赖诊断结论（用结论里的关键词去检索政策条款）。
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>单步超时 + 部分降级</b>：任何一步失败或超时，只把该步标记为失败，
 *       其余步骤继续，最后的结论会注明"部分数据缺失"。不因为一个数据源挂了就整体失败</li>
 *   <li><b>编排轨迹</b>：每步记录 step / ok / costMs / error，
 *       既用于排查，也让模型知道哪些信息是可信的</li>
 *   <li><b>每步一个 span</b>：挂在 {@code ai.chat} 与父工具 span 之下，
 *       链路里能直接看到"一次诊断跑了哪几步、各花多久"</li>
 *   <li><b>越权防护</b>：订单归属不符时直接返回"未找到"，不泄露订单是否存在</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDiagnosisOrchestrator {

    /** 并行步骤用的线程池：诊断是低频操作，池子不用大，但要能拒绝而不是无限堆积 */
    private static final ExecutorService PARALLEL_POOL = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "orchestrator-" + System.nanoTime() % 1000);
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** 单个步骤的超时阈值。诊断是多步聚合，单步卡住不该拖死整体 */
    private static final long STEP_TIMEOUT_MS = 1500;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IVoucherOrderService voucherOrderService;
    private final IVoucherService voucherService;
    private final ISeckillVoucherService seckillVoucherService;
    private final IShopService shopService;
    private final IVoucherReconcileLogService voucherReconcileLogService;
    private final RuleRetriever ruleRetriever;
    private final Tracer tracer;

    // ==================================================================
    // 对外入口
    // ==================================================================

    /**
     * 诊断一笔订单。返回结论 + 编排轨迹 + 相关规则。
     *
     * @param userId  当前用户（用于越权校验），可为 null 表示无身份
     * @param orderId 订单号
     */
    public DiagnosisResult diagnose(Long userId, Long orderId) {
        long start = System.currentTimeMillis();
        List<StepTrace> traces = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();

        // ---------- 阶段 1：并行（两者都只依赖 orderId）----------
        CompletableFuture<StepOutcome<VoucherOrder>> fOrder =
                runAsync("查订单", () -> voucherOrderService.getById(orderId));
        CompletableFuture<StepOutcome<List<VoucherReconcileLog>>> fReconcile =
                runAsync("查对账日志", () -> voucherReconcileLogService.lambdaQuery()
                        .eq(VoucherReconcileLog::getOrderId, orderId)
                        .list());

        StepOutcome<VoucherOrder> orderOut = await(fOrder, "查订单");
        StepOutcome<List<VoucherReconcileLog>> reconcileOut = await(fReconcile, "查对账日志");
        traces.add(orderOut.trace());
        traces.add(reconcileOut.trace());

        VoucherOrder order = orderOut.data();
        List<VoucherReconcileLog> reconcileLogs =
                reconcileOut.data() == null ? List.of() : reconcileOut.data();

        // ---------- 越权校验：归属不符时按"不存在"处理，不泄露存在性 ----------
        if (order != null && userId != null && !userId.equals(order.getUserId())) {
            log.warn("[ORCH] 越权拦截 userId={} orderId={}", userId, orderId);
            return new DiagnosisResult(
                    "未找到该订单", "NOT_FOUND",
                    "该订单号在您名下不存在。",
                    traces, data, elapsed(start), List.of());
        }

        // ---------- 阶段 2：查券 + 秒杀信息（依赖订单的 voucherId）----------
        Voucher voucher = null;
        SeckillVoucherFullModel seckill = null;
        if (order != null && order.getVoucherId() != null) {
            StepOutcome<Voucher> vOut = run("查券", () -> voucherService.getById(order.getVoucherId()));
            StepOutcome<SeckillVoucherFullModel> sOut = run("查秒杀信息",
                    () -> seckillVoucherService.queryByVoucherId(order.getVoucherId()));
            traces.add(vOut.trace());
            traces.add(sOut.trace());
            voucher = vOut.data();
            seckill = sOut.data();
        }

        // ---------- 阶段 3：查商铺（依赖券的 shopId）----------
        Shop shop = null;
        // 提取成 final 局部变量，才能在 lambda 里引用（voucher 本身会被重新赋值）
        final Long shopIdForQuery = voucher == null ? null : voucher.getShopId();
        if (shopIdForQuery != null) {
            StepOutcome<Shop> shOut = run("查商铺", () -> shopService.getById(shopIdForQuery));
            traces.add(shOut.trace());
            shop = shOut.data();
        }

        // ---------- 结构化数据（喂给模型与前端）----------
        if (order != null) {
            data.put("orderId", order.getId());
            data.put("orderStatus", order.getStatus() != null && order.getStatus() == 1 ? "正常" : "已取消");
            data.put("reconciliationStatus", reconciliationText(order.getReconciliationStatus()));
            data.put("createTime", format(order.getCreateTime()));
            data.put("payTime", format(order.getPayTime()));
            data.put("useTime", format(order.getUseTime()));
            data.put("refundTime", format(order.getRefundTime()));
        }
        data.put("reconcileLogCount", reconcileLogs.size());
        if (voucher != null) {
            data.put("voucherTitle", voucher.getTitle());
            data.put("voucherRules", voucher.getRules());
        }
        if (seckill != null) {
            data.put("seckillStock", seckill.getStock());
            data.put("seckillBeginTime", format(seckill.getBeginTime()));
            data.put("seckillEndTime", format(seckill.getEndTime()));
        }
        if (shop != null) {
            data.put("shopName", shop.getName());
            data.put("shopAddress", shop.getAddress());
        }

        // ---------- 阶段 4：诊断推理 ----------
        Diagnosis diagnosis = infer(order, reconcileLogs, voucher, seckill);

        // ---------- 阶段 5：规则检索（依赖诊断结论的关键词）----------
        List<RuleRetriever.ScoredRule> rules = List.of();
        if (diagnosis.ruleQuery() != null) {
            StepOutcome<List<RuleRetriever.ScoredRule>> rOut =
                    run("检索平台规则", () -> ruleRetriever.search(diagnosis.ruleQuery()));
            traces.add(rOut.trace());
            if (rOut.data() != null) {
                rules = rOut.data();
            }
        }

        // ---------- 部分降级提示 ----------
        long failed = traces.stream().filter(t -> !t.ok()).count();
        String conclusion = diagnosis.conclusion();
        String detail = diagnosis.detail();
        if (failed > 0) {
            detail = detail + "\n\n注意：本次诊断有 " + failed + " 个数据源未能取到，结论可能存在偏差。";
        }

        log.info("[ORCH] 诊断完成 orderId={} 步骤数={} 失败步数={} 耗时={}ms",
                orderId, traces.size(), failed, elapsed(start));

        return new DiagnosisResult(conclusion, diagnosis.severity(), detail,
                traces, data, elapsed(start), rules);
    }

    // ==================================================================
    // 诊断推理
    // ==================================================================

    /**
     * 把多源数据合成一个有业务含义的结论。
     * 这里体现的是<b>领域知识</b>：知道「订单不存在但对账日志有记录」意味着什么。
     */
    private Diagnosis infer(VoucherOrder order, List<VoucherReconcileLog> reconcileLogs,
                            Voucher voucher, SeckillVoucherFullModel seckill) {
        LocalDateTime now = LocalDateTime.now();

        // ---- 情况 1：订单不存在 ----
        if (order == null) {
            if (!reconcileLogs.isEmpty()) {
                return new Diagnosis(
                        "库存已扣减，但订单尚未生成",
                        "ATTENTION",
                        "秒杀 订单 处理中",
                        "检测到 " + reconcileLogs.size() + " 条与该订单号关联的库存扣减对账记录，"
                                + "但没有查到订单本身。\n"
                                + "这是秒杀异步下单的正常中间态：Redis 已原子扣减库存、消息已投递到 Kafka，"
                                + "但消费端还没把订单写入数据库。\n"
                                + "建议：等待几秒后重新查询，<b>不要重复下单</b>。"
                                + "若长时间查不到订单，对账任务会自动补偿或回滚库存。");
            }
            return new Diagnosis(
                    "未找到订单，也没有库存扣减记录",
                    "NOT_FOUND",
                    "订单 下单",
                    "系统中既没有这个订单，也没有与它关联的库存扣减记录。\n"
                            + "通常意味着订单号输入有误，或者该订单不属于当前账号。");
        }

        // ---- 情况 2：对账状态异常 ----
        Integer rs = order.getReconciliationStatus();
        if (rs != null && (rs == 2 || rs == 3)) {
            return new Diagnosis(
                    "订单与库存数据不一致，需要人工核对",
                    "ABNORMAL",
                    "对账 不一致 数据",
                    "订单确实存在，但对账状态为「" + reconciliationText(rs) + "」，"
                            + "说明订单记录与库存扣减流水核对不上。\n"
                            + "这类情况平台的对账任务会记录并告警，但<b>不会自动修改订单状态</b>（避免误伤正常订单），"
                            + "需要人工介入核对。建议提供订单号联系客服。");
        }

        // ---- 情况 3：订单已取消 ----
        if (order.getStatus() != null && order.getStatus() == 2) {
            boolean refunded = order.getRefundTime() != null;
            if (refunded) {
                return new Diagnosis(
                        "订单已取消，退款已完成",
                        "NORMAL",
                        "取消 退款 回流",
                        "订单状态为已取消，退款时间：" + format(order.getRefundTime()) + "。\n"
                                + "取消时库存已回流，其他用户可以继续抢购同一张券。");
            }
            return new Diagnosis(
                    "订单已取消，但无退款记录",
                    "ATTENTION",
                    "取消 退款",
                    "订单状态为已取消，但没有退款时间记录。\n"
                            + "最常见的原因是<b>超时未支付被系统自动关单</b>——这种情况本就没有实际支付，"
                            + "因此不产生退款记录。");
        }

        // ---- 情况 4：券活动已结束 ----
        if (seckill != null && seckill.getEndTime() != null && seckill.getEndTime().isBefore(now)) {
            return new Diagnosis(
                    "订单正常，但券的活动已结束",
                    "ATTENTION",
                    "有效期 活动时间",
                    "订单本身没问题，但该券的秒杀活动已于 " + format(seckill.getEndTime()) + " 结束。\n"
                            + "已抢到的订单依然有效，只是无法再购买同款券。");
        }

        // ---- 情况 5：一切正常 ----
        StringBuilder detail = new StringBuilder("订单状态正常，对账状态为「")
                .append(reconciliationText(rs)).append("」。");
        if (voucher != null) {
            detail.append("\n券：").append(voucher.getTitle());
            if (voucher.getRules() != null && !voucher.getRules().isBlank()) {
                detail.append("（使用规则：").append(voucher.getRules()).append("）");
            }
            detail.append("。");
        }
        if (seckill != null && seckill.getEndTime() != null) {
            detail.append("\n活动时间至 ").append(format(seckill.getEndTime())).append("。");
        }
        return new Diagnosis("订单正常", "NORMAL", "订单状态 正常", detail.toString());
    }

    // ==================================================================
    // 步骤执行：计时 + span + 失败降级
    // ==================================================================

    /** 同步执行一步：计时、建 span、吞掉异常并标记失败 */
    private <T> StepOutcome<T> run(String step, Supplier<T> action) {
        long t0 = System.currentTimeMillis();
        Span span = tracer.nextSpan().name("diagnose.step").tag("step.name", step).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            T data = action.get();
            long cost = System.currentTimeMillis() - t0;
            span.tag("step.ok", "true").tag("step.cost.ms", String.valueOf(cost));
            return StepOutcome.ok(step, data, cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - t0;
            span.tag("step.ok", "false").tag("step.cost.ms", String.valueOf(cost));
            span.error(e);
            log.warn("[ORCH] 步骤「{}」失败 耗时={}ms: {}", step, cost, e.toString());
            return StepOutcome.fail(step, cost, e.toString());
        } finally {
            span.end();
        }
    }

    /** 异步执行一步，用于并行阶段 */
    private <T> CompletableFuture<StepOutcome<T>> runAsync(String step, Supplier<T> action) {
        try {
            return CompletableFuture.supplyAsync(() -> run(step, action), PARALLEL_POOL);
        } catch (Exception e) {
            // 线程池拒绝：直接返回失败结果，不让整个诊断挂掉
            log.warn("[ORCH] 步骤「{}」提交失败（线程池拒绝）: {}", step, e.toString());
            CompletableFuture<StepOutcome<T>> f = new CompletableFuture<>();
            f.complete(StepOutcome.fail(step, 0L, "线程池拒绝: " + e.getMessage()));
            return f;
        }
    }

    /** 等待异步步骤，带超时；超时或异常都转成失败结果 */
    private <T> StepOutcome<T> await(CompletableFuture<StepOutcome<T>> future, String step) {
        try {
            // orTimeout 触发的超时会被包成 CompletionException，统一在下面处理
            return future.orTimeout(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                log.warn("[ORCH] 步骤「{}」超时 {}ms", step, STEP_TIMEOUT_MS);
                return StepOutcome.fail(step, STEP_TIMEOUT_MS, "超时 " + STEP_TIMEOUT_MS + "ms");
            }
            log.warn("[ORCH] 步骤「{}」异常: {}", step, cause.toString());
            return StepOutcome.fail(step, 0L, cause.toString());
        } catch (Exception e) {
            log.warn("[ORCH] 步骤「{}」异常: {}", step, e.toString());
            return StepOutcome.fail(step, 0L, e.toString());
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private String format(LocalDateTime t) {
        return t == null ? null : TIME_FMT.format(t);
    }

    private String reconciliationText(Integer s) {
        if (s == null) {
            return "未设置";
        }
        return switch (s) {
            case 1 -> "待处理";
            case 2 -> "异常";
            case 3 -> "不一致";
            case 4 -> "一致";
            default -> "未知(" + s + ")";
        };
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 单步执行结果（内部用，含数据） */
    private record StepOutcome<T>(String step, T data, boolean ok, long costMs, String error) {
        static <T> StepOutcome<T> ok(String step, T data, long costMs) {
            return new StepOutcome<>(step, data, true, costMs, null);
        }

        static <T> StepOutcome<T> fail(String step, long costMs, String error) {
            return new StepOutcome<>(step, null, false, costMs, error);
        }

        StepTrace trace() {
            return new StepTrace(step, ok, costMs, error);
        }
    }

    /** 编排轨迹中的一步，会返回给模型/前端 */
    public record StepTrace(String step, boolean ok, long costMs, String error) {
    }

    /** 诊断结论（内部推理结果） */
    private record Diagnosis(String conclusion, String severity, String ruleQuery, String detail) {
    }

    /**
     * 诊断最终结果。
     *
     * @param conclusion  一句话结论
     * @param severity    NORMAL / ATTENTION / ABNORMAL / NOT_FOUND
     * @param detail      详细解释（含给用户的建议）
     * @param traces      编排轨迹：每一步的名称、是否成功、耗时
     * @param data        结构化数据
     * @param totalCostMs 总耗时
     * @param rules       命中的平台规则（来自 RAG 检索）
     */
    public record DiagnosisResult(String conclusion, String severity, String detail,
                                  List<StepTrace> traces, Map<String, Object> data,
                                  long totalCostMs, List<RuleRetriever.ScoredRule> rules) {
    }
}
