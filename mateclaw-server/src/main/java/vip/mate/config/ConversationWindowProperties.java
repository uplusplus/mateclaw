package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话历史上下文窗口管理配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.agent.conversation.window")
public class ConversationWindowProperties {

    /** 全局默认最大输入 token（上下文窗口） */
    private int defaultMaxInputTokens = 128000;

    /** 历史 token 占比达此阈值触发压缩（0-1） */
    private double compactTriggerRatio = 0.75;

    /** 压缩后保留最近 N 轮对话（user+assistant 算一轮） */
    private int preserveRecentPairs = 5;

    /** 摘要自身最大 token 数（仅作 LLM maxToken 参数上限，实际预算由动态计算） */
    private int summaryMaxTokens = 800;

    // ==================== 动态压缩配置（Hermes 风格） ====================

    /** 尾部保护的最小消息数（即使 token 预算用完也至少保留这么多） */
    private int protectLastMinMessages = 10;

    /** 摘要 token 预算占被压缩内容的比例 (0-1)。被压缩内容越多，摘要越长。 */
    private double summaryBudgetRatio = 0.20;

    /** 摘要 token 预算上限（字数，非 token） */
    private int summaryBudgetCeiling = 3000;

    /** 摘要 token 预算下限（字数） */
    private int summaryBudgetFloor = 500;

    /**
     * Minimum prefix size (in messages) the pair-safe boundary must leave
     * before compaction is allowed to run. After enforcing tool-call/response
     * pair integrity the boundary may collapse so far forward that only a
     * handful of messages remain in the prefix — at that point the
     * compaction cost (a structured-summary LLM call) outweighs any token
     * savings, and we may as well skip this turn.
     *
     * <p>Default 2 means "at least two old messages worth condensing".
     * Set to 0 to always attempt compaction whenever a pair-safe cut exists.
     */
    private int pairSafeMinPrefixToCompact = 2;
}
