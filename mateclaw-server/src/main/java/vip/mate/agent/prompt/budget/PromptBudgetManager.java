package vip.mate.agent.prompt.budget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.StructuredTruncator;
import vip.mate.agent.context.TokenEstimator;

import java.util.*;

/**
 * Prompt 模块化预算管理器
 * <p>
 * 职责：
 * <ul>
 *   <li>按配置计算每个模块的 token 预算</li>
 *   <li>超限模块自动裁剪（调用 {@link StructuredTruncator}）</li>
 *   <li>收集度量数据供 UI 展示</li>
 * </ul>
 * <p>
 * 配置通过 {@link PromptBudgetConfigService} 从 DB 读取，
 * 支持全局默认 + Agent 级覆盖的二级继承。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBudgetManager {

    /** 安全余量：预留 5% 给不可预见的开销 */
    private static final double SAFETY_MARGIN = 0.05;

    private final PromptBudgetConfigService configService;

    // ======================================================================
    // Public API
    // ======================================================================

    /**
     * 判断模块是否启用。
     */
    public boolean isEnabled(PromptModule module, Long agentId) {
        ModuleConfig cfg = configService.resolve(module, agentId);
        return cfg.enabled;
    }

    /**
     * 核心：按预算组装 prompt。
     *
     * @param segments      各模块的原始内容（按拼接顺序）
     * @param contextWindow 模型的 context window token 上限
     * @param agentId       Agent ID（null = 使用全局默认配置）
     * @return 组装结果（含裁剪后内容 + 度量数据）
     */
    public AssembledPrompt assemble(List<PromptSegment> segments,
                                     int contextWindow, Long agentId) {
        if (segments == null || segments.isEmpty()) {
            return AssembledPrompt.empty(contextWindow);
        }

        // Step 1: 计算每个模块的预算
        Map<PromptModule, Integer> budgets = calculateBudgets(segments, contextWindow, agentId);

        // Step 2: 按预算裁剪
        List<PromptSegment> trimmed = new ArrayList<>();
        int totalTokens = 0;

        for (PromptSegment seg : segments) {
            Integer budget = budgets.get(seg.module());
            if (budget == null || budget <= 0) {
                trimmed.add(PromptSegment.disabled(seg.module()));
                continue;
            }

            if (seg.estimatedTokens() <= budget) {
                trimmed.add(seg);
                totalTokens += seg.estimatedTokens();
            } else {
                PromptSegment truncated = truncateSegment(seg, budget);
                trimmed.add(truncated);
                totalTokens += truncated.estimatedTokens();

                log.info("[PromptBudget] 模块 {} 超限: {} > {} tokens, 已截断",
                        seg.module().displayName(), seg.estimatedTokens(), budget);
            }
        }

        // Step 3: 收集度量
        List<PromptModuleMetrics> metrics = collectMetrics(trimmed, contextWindow, agentId);

        return new AssembledPrompt(trimmed, metrics, totalTokens, contextWindow);
    }

    /**
     * 仅收集度量（不裁剪），用于 live 预览。
     */
    public List<PromptModuleMetrics> preview(List<PromptSegment> segments,
                                              int contextWindow, Long agentId) {
        return collectMetrics(segments, contextWindow, agentId);
    }

    // ======================================================================
    // 预算计算
    // ======================================================================

    /**
     * 按配置计算每个模块的 token 预算。
     * <p>
     * 算法：
     * <ol>
     *   <li>必选模块直接按实际大小占用（不裁剪）</li>
     *   <li>可选模块按优先级从高到低分配剩余预算</li>
     *   <li>每个模块不超过 maxRatio × contextWindow 或 maxTokens</li>
     * </ol>
     */
    private Map<PromptModule, Integer> calculateBudgets(List<PromptSegment> segments,
                                                         int contextWindow, Long agentId) {
        Map<PromptModule, Integer> budgets = new LinkedHashMap<>();

        // Phase 1: 必选模块 — 不设上限，按实际大小占用
        int fixedCost = 0;
        List<PromptSegment> optionalSegments = new ArrayList<>();

        for (PromptSegment seg : segments) {
            ModuleConfig cfg = configService.resolve(seg.module(), agentId);
            if (!cfg.enabled) {
                budgets.put(seg.module(), 0);
                continue;
            }
            if (seg.module().required()) {
                budgets.put(seg.module(), Integer.MAX_VALUE);
                fixedCost += seg.estimatedTokens();
            } else {
                optionalSegments.add(seg);
            }
        }

        // Phase 2: 可选模块 — 按优先级分配剩余预算
        int available = (int) (contextWindow * (1.0 - SAFETY_MARGIN)) - fixedCost;
        available = Math.max(available, 0);

        // 按优先级降序排列（高优先级先分配）
        optionalSegments.sort((a, b) -> {
            ModuleConfig ca = configService.resolve(a.module(), agentId);
            ModuleConfig cb = configService.resolve(b.module(), agentId);
            return Integer.compare(cb.priority, ca.priority);
        });

        for (PromptSegment seg : optionalSegments) {
            ModuleConfig cfg = configService.resolve(seg.module(), agentId);

            // 计算该模块的上限
            int moduleCap = Integer.MAX_VALUE;
            if (cfg.maxTokens != null) {
                moduleCap = cfg.maxTokens;
            } else if (cfg.maxRatio != null) {
                moduleCap = (int) (contextWindow * cfg.maxRatio);
            } else if (seg.module().defaultMaxRatio() != null) {
                moduleCap = (int) (contextWindow * seg.module().defaultMaxRatio());
            }

            // 实际预算 = min(模块上限, 可用余额)
            int budget = Math.min(moduleCap, available);
            budgets.put(seg.module(), budget);

            // 从可用余额中扣除该模块的实际使用量
            available -= Math.min(seg.estimatedTokens(), budget);
            available = Math.max(available, 0);
        }

        return budgets;
    }

    // ======================================================================
    // 裁剪
    // ======================================================================

    /**
     * 将 segment 裁剪到指定 token 预算内。
     * 使用 {@link StructuredTruncator} 做 JSON 边界感知截断。
     */
    private PromptSegment truncateSegment(PromptSegment seg, int tokenBudget) {
        // 估算对应的字符预算（保守：1 token ≈ 2 字符）
        int charBudget = tokenBudget * 2;
        int headBudget = (int) (charBudget * 0.7);
        int tailBudget = charBudget - headBudget;

        String marker = "\n\n[... 已截断 (" + seg.module().displayName() + " 超出预算) ...]\n\n";
        String truncated = StructuredTruncator.truncate(
                seg.assembledContent(), headBudget, tailBudget, marker);

        String note = String.format("截断: %d → ~%d tokens (预算 %d)",
                seg.estimatedTokens(), TokenEstimator.estimateTokens(truncated), tokenBudget);

        return seg.withTruncation(truncated, note);
    }

    // ======================================================================
    // 度量收集
    // ======================================================================

    private List<PromptModuleMetrics> collectMetrics(List<PromptSegment> segments,
                                                      int contextWindow, Long agentId) {
        Map<PromptModule, Integer> budgets = calculateBudgets(segments, contextWindow, agentId);
        List<PromptModuleMetrics> result = new ArrayList<>();

        for (PromptSegment seg : segments) {
            ModuleConfig cfg = configService.resolve(seg.module(), agentId);
            Integer budget = budgets.getOrDefault(seg.module(), 0);
            double actualRatio = contextWindow > 0
                    ? (double) seg.estimatedTokens() / contextWindow : 0.0;

            result.add(new PromptModuleMetrics(
                    seg.module(),
                    seg.module().displayName(),
                    cfg.enabled,
                    seg.estimatedTokens(),
                    actualRatio,
                    cfg.maxRatio,
                    cfg.maxTokens,
                    cfg.priority,
                    seg.wasTruncated()
            ));
        }
        return result;
    }

    // ======================================================================
    // 内部数据结构
    // ======================================================================

    /**
     * 模块配置（从 DB 或默认值解析而来）
     */
    public record ModuleConfig(
        PromptModule module,
        boolean enabled,
        Double maxRatio,
        Integer maxTokens,
        int priority
    ) {}

    /**
     * 组装结果
     */
    public record AssembledPrompt(
        List<PromptSegment> segments,
        List<PromptModuleMetrics> metrics,
        int totalTokens,
        int contextWindow
    ) {
        public static AssembledPrompt empty(int contextWindow) {
            return new AssembledPrompt(List.of(), List.of(), 0, contextWindow);
        }

        /** 将所有 segment 的 assembledContent 拼接为最终 system prompt */
        public String toSystemPrompt() {
            StringBuilder sb = new StringBuilder();
            for (PromptSegment seg : segments) {
                if (seg.module().layer() == PromptModule.Layer.SYSTEM
                        && !seg.assembledContent().isEmpty()) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(seg.assembledContent());
                }
            }
            return sb.toString();
        }

        /** 将所有 Runtime/Dynamic 层 segment 拼接为 user message 前缀 */
        public String toRuntimePrefix() {
            StringBuilder sb = new StringBuilder();
            for (PromptSegment seg : segments) {
                if (seg.module().layer() != PromptModule.Layer.SYSTEM
                        && !seg.assembledContent().isEmpty()) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(seg.assembledContent());
                }
            }
            return sb.toString();
        }

        public double utilization() {
            return contextWindow > 0 ? (double) totalTokens / contextWindow : 0.0;
        }
    }

    /**
     * 模块度量（供 API 返回）
     */
    public record PromptModuleMetrics(
        PromptModule module,
        String displayName,
        boolean enabled,
        int estimatedTokens,
        double actualRatio,
        Double configuredMaxRatio,
        Integer configuredMaxTokens,
        int priority,
        boolean hitBudget
    ) {}
}
