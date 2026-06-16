package vip.mate.agent.prompt.budget;

/**
 * Prompt 模块枚举 — 每个可管理的 prompt 拼接单元。
 * <p>
 * 三层结构：
 * <ul>
 *   <li>{@link Layer#SYSTEM} — SystemMessage 层，高频缓存区</li>
 *   <li>{@link Layer#RUNTIME} — Runtime UserMessage 层，低频变化区</li>
 *   <li>{@link Layer#DYNAMIC} — Dynamic 层，每次请求变化</li>
 * </ul>
 */
public enum PromptModule {

    // === SystemMessage 层 ===
    AGENT_SYSTEM_PROMPT("Agent 系统提示", Layer.SYSTEM, true,  null, 100, true),
    SKILL_CATALOG(      "Skill Catalog",   Layer.SYSTEM, true,  0.15, 90,  false),
    WIKI_CONTEXT(       "Wiki 上下文",     Layer.SYSTEM, true,  0.10, 80,  false),
    TOOL_ENFORCEMENT(   "工具调用纪律",     Layer.SYSTEM, true,  0.05, 85,  false),

    // === Runtime UserMessage 层 ===
    RUNTIME_CONTEXT(    "运行时上下文",     Layer.RUNTIME, true,  null, 95,  false),
    PROGRESS_LEDGER(    "进度快照",        Layer.RUNTIME, true,  0.08, 75,  false),

    // === Dynamic 层 ===
    CONV_SUMMARY(       "对话摘要",        Layer.DYNAMIC, true,  0.20, 60,  false),
    TOOL_DEFINITIONS(   "工具定义",        Layer.DYNAMIC, true,  null, 100, true),
    MESSAGE_HISTORY(    "消息历史",        Layer.DYNAMIC, true,  null, 50,  true),
    CURRENT_USER_INPUT( "当前用户输入",     Layer.DYNAMIC, true,  null, 100, true);

    public enum Layer { SYSTEM, RUNTIME, DYNAMIC }

    private final String displayName;
    private final Layer layer;
    private final boolean defaultEnabled;
    /** 默认预算占比上限，null = 无限制 */
    private final Double defaultMaxRatio;
    /** 默认优先级，数字越大越优先保留 */
    private final int defaultPriority;
    /** 是否为必选模块（不可禁用） */
    private final boolean required;

    PromptModule(String displayName, Layer layer, boolean defaultEnabled,
                 Double defaultMaxRatio, int defaultPriority, boolean required) {
        this.displayName = displayName;
        this.layer = layer;
        this.defaultEnabled = defaultEnabled;
        this.defaultMaxRatio = defaultMaxRatio;
        this.defaultPriority = defaultPriority;
        this.required = required;
    }

    public String displayName()       { return displayName; }
    public Layer layer()              { return layer; }
    public boolean defaultEnabled()   { return defaultEnabled; }
    public Double defaultMaxRatio()   { return defaultMaxRatio; }
    public int defaultPriority()      { return defaultPriority; }
    public boolean required()         { return required; }
}
