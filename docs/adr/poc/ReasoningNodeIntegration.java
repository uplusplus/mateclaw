package vip.mate.agent.prompt.budget;

import vip.mate.agent.context.RuntimeContextInjector;
import vip.mate.agent.prompt.PromptLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * ReasoningNode 集成示例 — 展示如何用 PromptBudgetManager 替代硬编码拼接。
 * <p>
 * 这不是完整的 ReasoningNode，只展示 prompt 拼装部分的改造思路。
 */
public class ReasoningNodeIntegration {

    private final PromptBudgetManager budgetManager;

    public ReasoningNodeIntegration(PromptBudgetManager budgetManager) {
        this.budgetManager = budgetManager;
    }

    /**
     * 改造后的 prompt 拼装逻辑。
     * <p>
     * 原来：各模块直接 append 到 SystemMessage/UserMessage
     * 现在：各模块先收集为 PromptSegment，再由 BudgetManager 统一裁剪组装
     */
    public PromptBudgetManager.AssembledPrompt buildPrompt(
            String agentSystemPrompt,
            String skillCatalog,
            String wikiContext,
            String runtimeContext,
            String progressLedger,
            String conversationSummary,
            Long agentId,
            int contextWindow) {

        List<PromptSegment> segments = new ArrayList<>();

        // M1: Agent System Prompt (必选)
        segments.add(PromptSegment.of(PromptModule.AGENT_SYSTEM_PROMPT, agentSystemPrompt));

        // M2: Skill Catalog (可选)
        if (budgetManager.isEnabled(PromptModule.SKILL_CATALOG, agentId)) {
            segments.add(PromptSegment.of(PromptModule.SKILL_CATALOG, skillCatalog));
        }

        // M3: Wiki Context (可选)
        if (budgetManager.isEnabled(PromptModule.WIKI_CONTEXT, agentId)) {
            segments.add(PromptSegment.of(PromptModule.WIKI_CONTEXT, wikiContext));
        }

        // M4: TOOL_USE_ENFORCEMENT (可选)
        if (budgetManager.isEnabled(PromptModule.TOOL_ENFORCEMENT, agentId)) {
            String enforcement = PromptLoader.loadPrompt("graph/tool-use-enforcement");
            segments.add(PromptSegment.of(PromptModule.TOOL_ENFORCEMENT, enforcement));
        }

        // M5: Runtime Context (可选)
        if (budgetManager.isEnabled(PromptModule.RUNTIME_CONTEXT, agentId)) {
            segments.add(PromptSegment.of(PromptModule.RUNTIME_CONTEXT, runtimeContext));
        }

        // M6: Progress Ledger (可选)
        if (budgetManager.isEnabled(PromptModule.PROGRESS_LEDGER, agentId)) {
            segments.add(PromptSegment.of(PromptModule.PROGRESS_LEDGER, progressLedger));
        }

        // M7: Conversation Summary (可选)
        if (budgetManager.isEnabled(PromptModule.CONV_SUMMARY, agentId)
                && conversationSummary != null && !conversationSummary.isBlank()) {
            segments.add(PromptSegment.of(PromptModule.CONV_SUMMARY, conversationSummary));
        }

        // 统一组装
        return budgetManager.assemble(segments, contextWindow, agentId);
    }

    /**
     * 用法示例
     */
    public static void main(String[] args) {
        // 初始化
        PromptBudgetConfigService configService = new PromptBudgetConfigService();
        PromptBudgetManager manager = new PromptBudgetManager(configService);

        // 示例：禁用 Wiki，限制 Skill Catalog 为 10%
        configService.updateGlobal(
                PromptModule.WIKI_CONTEXT, false, null, null, null);
        configService.updateGlobal(
                PromptModule.SKILL_CATALOG, true, 0.10, null, null);

        // 模拟拼装
        ReasoningNodeIntegration integration = new ReasoningNodeIntegration(manager);
        PromptBudgetManager.AssembledPrompt result = integration.buildPrompt(
                "你是一个智能助手...",       // agentSystemPrompt
                "## Skills\n- read_file\n- shell\n...",  // skillCatalog
                "Wiki: MateClaw 是一个...",    // wikiContext (会被禁用)
                "[system-context] Current time: 2026-06-16 09:36 (Asia/Shanghai)",
                "## 当前任务进度\n- model_gpt4: done\n- model_claude: in_progress",
                "[上下文压缩] 更早的对话轮次已被压缩...",
                1L,                           // agentId
                128000                        // contextWindow
        );

        // 输出度量
        System.out.println("=== Prompt 预算报告 ===");
        System.out.printf("总 token: %d / %d (%.1f%%)%n",
                result.totalTokens(), result.contextWindow(),
                result.utilization() * 100);
        System.out.println();
        for (var m : result.metrics()) {
            String status = m.enabled() ? (m.hitBudget() ? "⚠️ 截断" : "✅") : "❌ 禁用";
            System.out.printf("  %-16s %6d tok  %s  (配置上限: %s)%n",
                    m.displayName(), m.estimatedTokens(), status,
                    m.configuredMaxRatio() != null
                            ? String.format("%.0f%%", m.configuredMaxRatio() * 100)
                            : "无限制");
        }
    }
}
