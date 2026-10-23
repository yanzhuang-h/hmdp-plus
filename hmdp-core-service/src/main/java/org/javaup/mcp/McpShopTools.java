package org.javaup.mcp;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具集：把平台的商户/优惠券/订单查询能力，以标准 MCP 工具的形式暴露给 AI 客户端。
 *
 * <p>设计约束：
 * <ul>
 *   <li>MCP 调用没有登录上下文，因此不能依赖 {@code UserHolder}；</li>
 *   <li>工具只做只读查询，不做写操作，避免 AI 误改业务数据；</li>
 *   <li>返回 JSON 字符串，便于大模型解析；异常统一转为友好文本，不抛堆栈。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpShopTools {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IShopService shopService;
    private final IVoucherService voucherService;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;

    @Tool(description = "根据商铺ID查询商铺详情，包括名称、商圈、地址、评分、人均消费、营业时间、销量")
    public String queryShopById(@ToolParam(description = "商铺ID，例如 1") Long shopId) {
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

    @Tool(description = "根据商铺ID查询该商铺下的全部优惠券，包含面额、售价、状态；若为秒杀券还会返回剩余库存与活动时间")
    public String queryShopVouchers(@ToolParam(description = "商铺ID，例如 1") Long shopId) {
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

    @Tool(description = "根据订单ID查询优惠券订单状态，返回订单状态、下单时间、支付时间、核销时间、退款时间")
    public String queryOrderStatus(@ToolParam(description = "订单ID（雪花ID，例如 2112869162882732034）") Long orderId) {
        try {
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
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
