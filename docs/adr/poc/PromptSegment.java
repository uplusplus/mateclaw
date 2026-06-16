package vip.mate.agent.prompt.budget;

import vip.mate.agent.context.TokenEstimator;

/**
 * 一个 prompt 模块的运行时表示 — 包含原始内容、裁剪后内容、token 度量。
 */
public record PromptSegment(
    PromptModule module,
    String rawContent,
    String assembledContent,
    int estimatedTokens,
    boolean wasTruncated,
    String truncationNote
) {
    /** 从原始内容创建未裁剪的 segment */
    public static PromptSegment of(PromptModule module, String content) {
        if (content == null) content = "";
        int tokens = TokenEstimator.estimateTokens(content);
        return new PromptSegment(module, content, content, tokens, false, null);
    }

    /** 创建一个被裁剪后的 segment 副本 */
    public PromptSegment withTruncation(String trimmedContent, String note) {
        int newTokens = TokenEstimator.estimateTokens(trimmedContent);
        return new PromptSegment(module, rawContent, trimmedContent, newTokens, true, note);
    }

    /** 创建一个被禁用的空 segment */
    public static PromptSegment disabled(PromptModule module) {
        return new PromptSegment(module, "", "", 0, false, "disabled");
    }
}
