package org.javaup.mcp.rag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.javaup.mcp.rag.RuleRetriever.ScoredRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台规则 RAG 的检索质量评估。
 *
 * <p>这是 RAG 最容易被忽略、也最能体现工程严谨性的一步：
 * 不是「能返回规则」就算成功，而要验证<b>该命中的命中、不该命中的不返回</b>。
 *
 * <p>评估集覆盖 5 个主题共 10 个典型问法，包含：
 * <ul>
 *   <li>规则原词提问（应直接命中）</li>
 *   <li>口语化提问（验证同义词扩展）</li>
 *   <li>规则库里没有的问题（验证不会硬凑答案）</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleRetrieverTest {

    private RuleRetriever retriever;

    @BeforeAll
    void setUp() {
        retriever = new RuleRetriever();
        retriever.load();
    }

    @Test
    @DisplayName("规则库应加载出全部 27 条规则")
    void shouldLoadAllRules() {
        System.out.println("[RAG] 规则库条数 = " + retriever.size());
        assertEquals(27, retriever.size(), "规则条数应与 5 个 md 文件中的二级标题总数一致");
    }

    @Test
    @DisplayName("评估集 1-8：应命中的典型问题")
    void shouldRetrieveExpectedRules() {
        // [问题, 期望命中的标题关键字]
        String[][] cases = {
                {"券能退吗", "退款"},
                {"为什么抢到了却没有订单", "订单"},
                {"一个人能抢几张", "限购"},
                {"秒杀券库存怎么保证不超卖", "超卖"},
                {"优惠券的有效期怎么算", "有效期"},
                {"代金券的面额和售价有什么区别", "面额"},
                {"订阅了为什么没收到提醒", "提醒"},
                {"为什么数据看起来是旧的", "旧值"}
        };

        int passed = 0;
        for (String[] c : cases) {
            String query = c[0];
            String expected = c[1];
            List<ScoredRule> results = retriever.search(query);
            String joined = results.stream().map(r -> r.chunk().title()).reduce("", (a, b) -> a + "|" + b);
            boolean hit = joined.contains(expected) || results.stream()
                    .anyMatch(r -> r.chunk().content().contains(expected));

            System.out.printf("[RAG] %-24s → score=%.2f  %s  %s%n", query,
                    results.isEmpty() ? 0.0 : results.get(0).score(),
                    results.isEmpty() ? "<无结果>" : results.get(0).chunk().title(),
                    hit ? "✅" : "❌ 期望含「" + expected + "」");
            if (hit) {
                passed++;
            }
        }

        System.out.println("[RAG] 命中率 = " + passed + "/" + cases.length);
        // 允许 1 题不中，因为关键词检索对极端口语化表述存在局限
        assertTrue(passed >= cases.length - 1,
                "命中率应 >= 7/8，实际 " + passed + "/" + cases.length);
    }

    @Test
    @DisplayName("评估集 9：口语化提问（验证同义词扩展生效）")
    void shouldExpandSynonyms() {
        // 「退钱」不在任何规则原文里，只能靠同义词映射到「退款」
        List<ScoredRule> r1 = retriever.search("我想退钱怎么弄");
        System.out.println("[RAG] 『我想退钱怎么弄』→ "
                + (r1.isEmpty() ? "<无结果>" : r1.get(0).chunk().title()));
        assertFalse(r1.isEmpty(), "同义词「退钱→退款」应能召回规则");
        assertTrue(r1.get(0).chunk().title().contains("退款")
                        || r1.get(0).chunk().content().contains("退款"),
                "应命中退款相关规则");

        // 「抢不到」同理
        List<ScoredRule> r2 = retriever.search("一直抢不到券");
        System.out.println("[RAG] 『一直抢不到券』→ "
                + (r2.isEmpty() ? "<无结果>" : r2.get(0).chunk().title()));
        assertFalse(r2.isEmpty(), "同义词「抢不到→失败」应能召回规则");
    }

    @Test
    @DisplayName("评估集 10：规则库里没有的问题，应返回空而不是硬凑")
    void shouldReturnEmptyForUnknownTopic() {
        // 这个问题在规则库里完全没有对应内容（涉及支付渠道，项目未实现）
        List<ScoredRule> results = retriever.search("支持哪些第三方支付渠道");
        if (results.isEmpty()) {
            System.out.println("[RAG] 『支持哪些第三方支付渠道』→ <无结果> ✅");
        } else {
            System.out.println("[RAG] 『支持哪些第三方支付渠道』→ 命中 " + results.size() + " 条：");
            results.forEach(r -> System.out.printf("[RAG]    score=%.2f  %s%n", r.score(), r.chunk().title()));
        }
        assertTrue(results.isEmpty(),
                "规则库无相关内容时应返回空，让模型明确说『暂无相关规则』而不是编造。实际返回: "
                        + results.size() + " 条");
    }
}
