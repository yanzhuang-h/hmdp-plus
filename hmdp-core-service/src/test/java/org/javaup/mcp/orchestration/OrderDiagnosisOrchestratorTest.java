package org.javaup.mcp.orchestration;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import io.micrometer.tracing.Tracer;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.entity.VoucherReconcileLog;
import org.javaup.mcp.orchestration.OrderDiagnosisOrchestrator.DiagnosisResult;
import org.javaup.mcp.orchestration.OrderDiagnosisOrchestrator.StepTrace;
import org.javaup.mcp.rag.RuleRetriever;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 订单诊断编排器的行为验证。
 *
 * <p>重点验证四件事，这四件事正是「自己写编排」相对于「让模型自由调工具」的价值所在：
 * <ol>
 *   <li><b>依赖顺序</b>：订单 → 券 → 商铺，后一步要用前一步的值</li>
 *   <li><b>并行</b>：订单与对账日志同时查</li>
 *   <li><b>部分降级</b>：某一步失败，其余继续，结论里注明数据缺失</li>
 *   <li><b>诊断推理</b>：同样的数据，不同组合要得出不同结论</li>
 * </ol>
 */
class OrderDiagnosisOrchestratorTest {

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 2001L;
    private static final Long VOUCHER_ID = 3001L;
    private static final Long SHOP_ID = 4001L;

    private IVoucherOrderService voucherOrderService;
    private IVoucherService voucherService;
    private ISeckillVoucherService seckillVoucherService;
    private IShopService shopService;
    private IVoucherReconcileLogService reconcileLogService;
    private RuleRetriever ruleRetriever;
    private OrderDiagnosisOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        voucherOrderService = mock(IVoucherOrderService.class);
        voucherService = mock(IVoucherService.class);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        shopService = mock(IShopService.class);
        reconcileLogService = mock(IVoucherReconcileLogService.class);
        ruleRetriever = mock(RuleRetriever.class);

        // 对账日志的链式查询需要单独 mock
        LambdaQueryChainWrapper<VoucherReconcileLog> wrapper = mock(LambdaQueryChainWrapper.class);
        when(reconcileLogService.lambdaQuery()).thenReturn(wrapper);
        when(wrapper.eq(any(), any())).thenReturn(wrapper);
        when(wrapper.list()).thenReturn(List.of());

        when(ruleRetriever.search(any())).thenReturn(List.of());

        orchestrator = new OrderDiagnosisOrchestrator(
                voucherOrderService, voucherService, seckillVoucherService,
                shopService, reconcileLogService, ruleRetriever, Tracer.NOOP);
    }

    @Test
    @DisplayName("正常订单：按依赖顺序取全部数据源，轨迹完整，结论为 NORMAL")
    void shouldDiagnoseNormalOrder() {
        when(voucherOrderService.getById(ORDER_ID)).thenReturn(order(1, 4));
        when(voucherService.getById(VOUCHER_ID)).thenReturn(voucher());
        when(seckillVoucherService.queryByVoucherId(VOUCHER_ID)).thenReturn(seckill());
        when(shopService.getById(SHOP_ID)).thenReturn(shop());

        DiagnosisResult r = orchestrator.diagnose(USER_ID, ORDER_ID);

        System.out.println("[ORCH-TEST] 结论=" + r.conclusion() + " 严重度=" + r.severity());
        System.out.println("[ORCH-TEST] 轨迹=" + r.traces());
        System.out.println("[ORCH-TEST] 总耗时=" + r.totalCostMs() + "ms");

        assertEquals("NORMAL", r.severity());
        assertEquals("订单正常", r.conclusion());

        // 依赖链完整：订单 → 券 → 秒杀 → 商铺
        List<String> steps = r.traces().stream().map(StepTrace::step).toList();
        assertTrue(steps.contains("查订单"), "应有查订单步骤");
        assertTrue(steps.contains("查对账日志"), "应有查对账日志步骤（与订单并行）");
        assertTrue(steps.contains("查券"), "应有查券步骤（依赖订单的 voucherId）");
        assertTrue(steps.contains("查秒杀信息"), "应有查秒杀步骤");
        assertTrue(steps.contains("查商铺"), "应有查商铺步骤（依赖券的 shopId）");

        // 商铺确实被取到（证明依赖链真的串起来了，不是空跑）
        assertEquals("103茶餐厅", r.data().get("shopName"));

        // 每步都成功
        assertTrue(r.traces().stream().allMatch(StepTrace::ok), "所有步骤都应成功");
    }

    @Test
    @DisplayName("订单不存在但对账日志有记录：诊断为「库存已扣减但订单未生成」")
    void shouldDetectAsyncPendingOrder() {
        when(voucherOrderService.getById(ORDER_ID)).thenReturn(null);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<VoucherReconcileLog> wrapper = mock(LambdaQueryChainWrapper.class);
        when(reconcileLogService.lambdaQuery()).thenReturn(wrapper);
        when(wrapper.eq(any(), any())).thenReturn(wrapper);
        when(wrapper.list()).thenReturn(List.of(reconcileLog()));

        DiagnosisResult r = orchestrator.diagnose(USER_ID, ORDER_ID);

        System.out.println("[ORCH-TEST] 异步中间态 结论=" + r.conclusion() + " 严重度=" + r.severity());

        assertEquals("ATTENTION", r.severity());
        assertTrue(r.conclusion().contains("订单尚未生成"), "结论应指出订单尚未生成");
        assertTrue(r.detail().contains("不要重复下单"), "应给出可操作建议");
        assertEquals(1, r.data().get("reconcileLogCount"));
    }

    @Test
    @DisplayName("越权：订单属于别人时返回 NOT_FOUND，不泄露订单存在性")
    void shouldRejectCrossUserOrder() {
        VoucherOrder other = order(1, 4);
        other.setUserId(9999L);
        when(voucherOrderService.getById(ORDER_ID)).thenReturn(other);

        DiagnosisResult r = orchestrator.diagnose(USER_ID, ORDER_ID);

        assertEquals("NOT_FOUND", r.severity());
        assertEquals("未找到该订单", r.conclusion());
        // 不应继续去查券/商铺（避免无谓查询与信息泄露）
        assertFalse(r.data().containsKey("voucherTitle"), "越权时不应加载券信息");
    }

    @Test
    @DisplayName("部分降级：券查询抛异常时其余步骤继续，结论注明数据缺失")
    void shouldDegradePartiallyWhenOneStepFails() {
        when(voucherOrderService.getById(ORDER_ID)).thenReturn(order(1, 4));
        when(voucherService.getById(VOUCHER_ID)).thenThrow(new RuntimeException("券服务超时"));

        DiagnosisResult r = orchestrator.diagnose(USER_ID, ORDER_ID);

        System.out.println("[ORCH-TEST] 降级 结论=" + r.conclusion());
        System.out.println("[ORCH-TEST] 降级 轨迹=" + r.traces());

        // 失败的那一步被标记出来
        StepTrace voucherStep = r.traces().stream()
                .filter(t -> t.step().equals("查券"))
                .findFirst()
                .orElseThrow();
        assertFalse(voucherStep.ok(), "查券步骤应标记为失败");
        assertNotNull(voucherStep.error(), "失败步骤应带错误信息");

        // 但因为订单查到了、对账也查到了，整体仍能得出「订单正常」的结论
        assertNotNull(r.conclusion(), "整体仍应返回结论，而不是整体失败");
        // 并且明确告知有数据源没取到
        assertTrue(r.detail().contains("未能取到"), "结论应注明部分数据缺失");
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private VoucherOrder order(int status, int reconciliationStatus) {
        VoucherOrder o = new VoucherOrder();
        o.setId(ORDER_ID);
        o.setUserId(USER_ID);
        o.setVoucherId(VOUCHER_ID);
        o.setStatus(status);
        o.setReconciliationStatus(reconciliationStatus);
        o.setCreateTime(LocalDateTime.now().minusHours(1));
        return o;
    }

    private Voucher voucher() {
        Voucher v = new Voucher();
        v.setId(VOUCHER_ID);
        v.setShopId(SHOP_ID);
        v.setTitle("80元代金券");
        v.setRules("周一至周日均可用");
        return v;
    }

    private SeckillVoucherFullModel seckill() {
        SeckillVoucherFullModel s = new SeckillVoucherFullModel();
        s.setVoucherId(VOUCHER_ID);
        s.setStock(200);
        s.setBeginTime(LocalDateTime.now().minusDays(1));
        s.setEndTime(LocalDateTime.now().plusDays(5));
        return s;
    }

    private Shop shop() {
        Shop s = new Shop();
        s.setId(SHOP_ID);
        s.setName("103茶餐厅");
        s.setAddress("金华路锦昌文华苑29号");
        return s;
    }

    private VoucherReconcileLog reconcileLog() {
        VoucherReconcileLog log = new VoucherReconcileLog();
        log.setOrderId(ORDER_ID);
        log.setUserId(USER_ID);
        log.setVoucherId(VOUCHER_ID);
        log.setReconciliationStatus(1);
        return log;
    }
}
