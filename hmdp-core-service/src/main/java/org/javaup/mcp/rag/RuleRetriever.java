package org.javaup.mcp.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台规则检索器（RAG 的 Retrieval 部分）。
 *
 * <p><b>为什么不用向量检索</b>：DeepSeek 没有 embedding 接口，而规则文档是结构化短文本、
 * 术语高度集中，关键词 + 中文 bigram + 同义词扩展的召回率足够。引入本地 embedding 模型
 * 或额外 API 都不划算。详见 docs/RAG设计.md。
 *
 * <p><b>实现要点</b>：
 * <ol>
 *   <li><b>加载</b>：启动时扫描 {@code classpath:rules/*.md}，只读一次</li>
 *   <li><b>切分</b>：按二级标题（{@code ## }）切，一行标题 + 其下正文为一个 chunk。
 *       规则本身就是「一条一个知识点」，硬按字数切会切断语义</li>
 *   <li><b>分词</b>：中文取 2-gram（滑动窗口），英文数字保留原词，过滤停用词</li>
 *   <li><b>打分</b>：简化 BM25 —— tf × idf，idf = log(N / df)</li>
 *   <li><b>扩展</b>：口语词映射到规则术语（退钱→退款、抢不到→失败）</li>
 * </ol>
 */
@Slf4j
@Component
public class RuleRetriever {

    private static final String RULES_PATTERN = "classpath*:rules/*.md";

    /** 检索返回的最大条数 */
    private static final int DEFAULT_TOP_K = 3;

    /** 低于该分数的结果视为不相关，避免把无关规则塞给模型 */
    private static final double MIN_SCORE = 1.0;

    /** 口语 → 规则术语的同义词扩展表 */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("退钱", List.of("退款")),
            Map.entry("退货", List.of("退款")),
            Map.entry("抢不到", List.of("失败")),
            Map.entry("没抢到", List.of("失败")),
            Map.entry("抢了没反应", List.of("处理中")),
            Map.entry("没生成订单", List.of("订单")),
            Map.entry("看不到订单", List.of("订单")),
            Map.entry("重复买", List.of("限购")),
            Map.entry("买多张", List.of("限购")),
            Map.entry("卡住", List.of("处理中")),
            Map.entry("过期了", List.of("有效期")),
            Map.entry("叠加", List.of("叠加")),
            Map.entry("抵扣多少", List.of("面额")),
            Map.entry("扣多少", List.of("面额")),
            Map.entry("限流", List.of("频繁")),
            Map.entry("被限制", List.of("频繁")),
            Map.entry("数据不对", List.of("不一致")),
            Map.entry("提醒", List.of("订阅"))
    );

    /** 停用词：出现频率极高但不携带信息 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "什么", "怎么", "为什么", "可以", "能", "吗", "呢", "啊", "这个", "那个",
            "请问", "如何", "哪些", "有没有"
    );

    /** 全部规则片段 */
    private final List<RuleChunk> chunks = new ArrayList<>();

    /** term -> 包含该 term 的 chunk 数，用于算 idf */
    private final Map<String, Integer> documentFrequency = new HashMap<>();

    /**
     * 一个可独立检索的规则片段。
     */
    public record RuleChunk(String source, String title, String content, List<String> tokens) {
    }

    /**
     * 检索结果（带分数，便于调试与评估）。
     */
    public record ScoredRule(RuleChunk chunk, double score) {
    }

    @PostConstruct
    public void load() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(RULES_PATTERN);
            for (Resource resource : resources) {
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String fileName = resource.getFilename() == null ? "unknown" : resource.getFilename();
                List<RuleChunk> parsed = parse(fileName, text);
                chunks.addAll(parsed);
            }
            buildDocumentFrequency();
            log.info("[RAG] 规则库加载完成：{} 个文件，{} 条规则",
                    resolver.getResources(RULES_PATTERN).length, chunks.size());
        } catch (IOException e) {
            log.error("[RAG] 规则库加载失败，规则问答将不可用", e);
        }
    }

    /**
     * 按二级标题切分。标题与正文拼成一个片段，标题里的词权重更高（见 {@link #search}）。
     */
    private List<RuleChunk> parse(String source, String text) {
        List<RuleChunk> result = new ArrayList<>();
        String[] lines = text.split("\n");
        String currentTitle = null;
        StringBuilder body = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                // 遇到新标题，先把上一段收尾
                if (currentTitle != null) {
                    result.add(build(source, currentTitle, body.toString()));
                }
                currentTitle = line.substring(3).trim();
                body.setLength(0);
            } else if (currentTitle != null) {
                body.append(line).append('\n');
            }
        }
        if (currentTitle != null) {
            result.add(build(source, currentTitle, body.toString()));
        }
        return result;
    }

    private RuleChunk build(String source, String title, String body) {
        String content = body.trim();
        // 标题的词也参与索引，但用独立字段，检索时给更高权重
        List<String> tokens = tokenize(title + " " + content);
        return new RuleChunk(source, title, content, tokens);
    }

    /**
     * 中文 2-gram + 英文/数字原词。
     */
    private List<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return new ArrayList<>(tokens);
        }
        String normalized = text.toLowerCase().replaceAll("[\\p{Punct}\\s]+", " ");

        // 英文单词 / 数字
        for (String word : normalized.split("\\s+")) {
            if (word.isBlank() || word.length() < 2) {
                continue;
            }
            if (STOP_WORDS.contains(word)) {
                continue;
            }
            tokens.add(word);
        }

        // 中文 2-gram：只在纯中文片段上做，避免把英文拆碎
        for (String segment : text.split("[^\\u4e00-\\u9fa5]+")) {
            if (segment.length() < 2) {
                continue;
            }
            for (int i = 0; i + 2 <= segment.length(); i++) {
                String bigram = segment.substring(i, i + 2);
                if (STOP_WORDS.contains(bigram)) {
                    continue;
                }
                tokens.add(bigram);
            }
        }
        return new ArrayList<>(tokens);
    }

    private void buildDocumentFrequency() {
        documentFrequency.clear();
        for (RuleChunk chunk : chunks) {
            for (String token : new LinkedHashSet<>(chunk.tokens())) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
    }

    /**
     * 检索 topK 条相关规则。
     *
     * <p>打分：对 query 的每个 term，若 chunk 含该 term，累加 {@code tf * idf}。
     * 标题命中的 term 额外加权，因为规则标题最能代表主题。
     */
    public List<ScoredRule> search(String query) {
        if (query == null || query.isBlank() || chunks.isEmpty()) {
            return List.of();
        }

        List<String> queryTokens = expandSynonyms(tokenize(query));
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        int totalChunks = chunks.size();
        List<ScoredRule> scored = new ArrayList<>();

        for (RuleChunk chunk : chunks) {
            Map<String, Integer> tf = new HashMap<>();
            for (String token : chunk.tokens()) {
                tf.merge(token, 1, Integer::sum);
            }
            List<String> titleTokens = tokenize(chunk.title());

            double rawScore = 0.0;
            for (String term : queryTokens) {
                Integer freq = tf.get(term);
                if (freq == null) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 1);
                double idf = Math.log((double) totalChunks / df) + 1.0;
                double weight = titleTokens.contains(term) ? 2.0 : 1.0;
                rawScore += freq * idf * weight;
            }

            // 长度归一化（对应标准 BM25 的 b 参数作用）：
            // 不加这一步，长 query 命中的 bigram 更多，分数天然更高，
            // 会导致「支持哪些第三方支付渠道」这种无关问题分数(6.80) 高于
            // 「券能退吗」这种真正相关的短问(4.30)，阈值无论怎么调都切不开。
            // 除以 query 的 token 数后：负例 0.85，正例最低 1.43，可以区分。
            double score = rawScore / queryTokens.size();
            if (score >= MIN_SCORE) {
                scored.add(new ScoredRule(chunk, score));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredRule::score).reversed());
        return scored.size() > DEFAULT_TOP_K ? scored.subList(0, DEFAULT_TOP_K) : scored;
    }

    /**
     * 同义词扩展：口语说法映射到规则术语，提升召回。
     */
    private List<String> expandSynonyms(List<String> tokens) {
        List<String> expanded = new ArrayList<>(tokens);
        for (String token : tokens) {
            List<String> mapped = SYNONYMS.get(token);
            if (mapped != null) {
                for (String m : mapped) {
                    expanded.addAll(tokenize(m));
                }
            }
        }
        return expanded;
    }

    /** 供评估使用：规则库总条数 */
    public int size() {
        return chunks.size();
    }
}
