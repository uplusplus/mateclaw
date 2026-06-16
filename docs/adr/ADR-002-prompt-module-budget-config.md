# ADR-002: Prompt 模块化预算配置方案

- **状态**: 设计稿
- **日期**: 2026-06-16
- **关联**: ADR-001 (MateClaw Prompt 架构分析)

## 1. 问题

当前 MateClaw 的 prompt 各模块（系统提示、Skill Catalog、Wiki、工具纪律、运行时上下文等）是硬编码拼接的：

- **无法开关**：想测试某个模块对回答质量的影响，只能改代码
- **无法感知大小**：不知道每个模块实际占了多少 token
- **无法控制预算**：小上下文模型下，多个模块可能挤占消息历史空间，无法按优先级分配

## 2. 设计目标

| 目标 | 说明 |
|------|------|
| 可观测 | 每个模块实时显示 token 估算值 |
| 可开关 | 每个模块独立启用/禁用 |
| 可配比 | 每个模块可设预算占比上限 |
| 兼容现有 | 不改现有 prompt 内容，只加配置层 |
| 不击穿 cache | 配置变更频率低，不影响 prompt cache 策略 |

## 3. 模块清单

基于 ADR-001 分析，需纳入管理的模块：

```
┌─────────────────────────────────────────────────────────────┐
│                    Prompt 模块分层                           │
├─────────────────────────────────────────────────────────────┤
│ SystemMessage 层 (高频缓存区)                                │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ M1: Agent System Prompt        [必选·不可禁用]        │  │
│  │ M2: Skill Catalog              [可选]                 │  │
│  │ M3: Wiki Context               [可选]                 │  │
│  │ M4: TOOL_USE_ENFORCEMENT       [可选]                 │  │
│  └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│ Runtime UserMessage 层 (低频变化区)                          │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ M5: Runtime Context            [可选]                 │  │
│  │ M6: Progress Ledger            [可选]                 │  │
│  └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│ Dynamic 层 (每次请求变化)                                    │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ M7: Conversation Summary       [可选]                 │  │
│  │ M8: Tool Definitions           [必选·有工具时不可禁]  │  │
│  │ M9: Message History            [必选·核心]            │  │
│  │ M10: Current User Input        [必选·核心]            │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 4. 数据模型

### 4.1 配置 Entity

```java
@Entity
@Table(name = "mate_prompt_budget_config")
public class PromptBudgetConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的 Agent ID，null 表示全局默认 */
    private Long agentId;

    /** 模块标识符 */
    @Enumerated(EnumType.STRING)
    private PromptModule module;

    /** 是否启用 */
    private boolean enabled = true;

    /**
     * 预算占比上限 (0.0 ~ 1.0)
     * 表示该模块最多占用 context window 的百分比
     * null = 不设上限（使用模块自身默认值）
     */
    private Double maxRatio;

    /**
     * 绝对 token 上限（优先于 maxRatio）
     * null = 不设绝对上限
     */
    private Integer maxTokens;

    /**
     * 优先级 (数字越小越优先保留)
     * 当总预算超限时，按优先级从低到高裁剪
     */
    private int priority;
}
```

### 4.2 模块枚举

```java
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
    /** 默认优先级 */
    private final int defaultPriority;
    /** 是否为必选模块（不可禁用） */
    private final boolean required;
}
```

### 4.3 运行时度量 DTO

```java
public record PromptModuleMetrics(
    PromptModule module,
    String displayName,
    boolean enabled,
    int estimatedTokens,
    Double actualRatio,        // 实际占 context window 的比例
    Double configuredMaxRatio, // 配置的上限
    Integer configuredMaxTokens,
    int priority,
    boolean hitBudget          // 是否被预算截断
) {}
```

## 5. 核心设计：PromptBudgetManager

### 5.1 职责

```
PromptBudgetManager
├── resolveConfig(agentId)    → 合并 Agent 级 + 全局默认配置
├── calculateBudgets()        → 按配置计算每个模块的 token 预算
├── buildPrompt()             → 按预算拼装 prompt，超限模块自动裁剪
├── collectMetrics()          → 收集实际 token 使用量
└── reportToUI()              → 输出 JSON 供前端展示
```

### 5.2 预算分配算法

```
输入:
  contextWindow = model.maxInputTokens
  modules[] = 各模块配置 (enabled, maxRatio, maxTokens, priority)

Step 1: 计算必选模块的固定开销
  fixedCost = Σ (必选模块的 estimatedTokens)
  可分配预算 = contextWindow - fixedCost - safetyMargin(5%)

Step 2: 按优先级分配可选模块预算
  sorted = modules.filter(m -> m.enabled && !m.required).sortBy(priority DESC)

  for each module in sorted:
    module.budget = min(
      module.maxTokens,
      contextWindow × module.maxRatio,
      可分配剩余
    )
    if module.estimatedTokens > module.budget:
      module.needsTrim = true
    可分配剩余 -= min(module.estimatedTokens, module.budget)

Step 3: 超限降级
  if 可分配剩余 < 0:
    按优先级从低到高逐个禁用/裁剪，直到余额 >= 0
```

### 5.3 拼装流程（改造后的 ReasoningNode）

```java
public class ReasoningNode {

    // 原来的拼装逻辑拆成两步：
    // Step A: 收集各模块原始内容
    // Step B: PromptBudgetManager 按预算组装

    List<PromptSegment> segments = new ArrayList<>();

    // M1: Agent System Prompt (必选)
    segments.add(PromptSegment.of(AGENT_SYSTEM_PROMPT, agentSystemPrompt));

    // M2: Skill Catalog (可选)
    if (budgetManager.isEnabled(SKILL_CATALOG)) {
        String catalog = skillCatalogRenderer.render(...);
        segments.add(PromptSegment.of(SKILL_CATALOG, catalog));
    }

    // M3: Wiki Context (可选)
    if (budgetManager.isEnabled(WIKI_CONTEXT)) {
        String wiki = wikiContextService.inject(...);
        segments.add(PromptSegment.of(WIKI_CONTEXT, wiki));
    }

    // M4: TOOL_USE_ENFORCEMENT (可选)
    if (budgetManager.isEnabled(TOOL_ENFORCEMENT)) {
        segments.add(PromptSegment.of(TOOL_ENFORCEMENT, TOOL_USE_ENFORCEMENT));
    }

    // ... M5-M10 同理

    // 统一由 BudgetManager 裁剪 + 拼装
    AssembledPrompt result = budgetManager.assemble(segments, contextWindow);
```

### 5.4 PromptSegment 结构

```java
public record PromptSegment(
    PromptModule module,
    String rawContent,          // 原始内容
    String assembledContent,    // 裁剪后的内容（由 BudgetManager 填充）
    int estimatedTokens,        // 估算 token 数
    boolean wasTruncated,       // 是否被裁剪
    String truncationNote       // 裁剪说明（如 "已截断至 2000 token"）
) {}
```

## 6. 可观测性设计

### 6.1 API 端点

```
GET /api/agents/{agentId}/prompt-budget
  → 返回各模块配置 + 当前 token 使用量

GET /api/agents/{agentId}/prompt-budget/live?conversationId=xxx
  → 返回实时度量（包含最近一次请求的实际 token 分布）

PUT /api/agents/{agentId}/prompt-budget
  Body: { "modules": [{ "module": "SKILL_CATALOG", "enabled": false }, ...] }
  → 更新配置
```

### 6.2 响应格式

```json
{
  "agentId": 1,
  "contextWindow": 128000,
  "totalEstimatedTokens": 45230,
  "utilization": 0.353,
  "modules": [
    {
      "module": "AGENT_SYSTEM_PROMPT",
      "displayName": "Agent 系统提示",
      "enabled": true,
      "required": true,
      "estimatedTokens": 2800,
      "actualRatio": 0.022,
      "configuredMaxRatio": null,
      "priority": 100,
      "hitBudget": false
    },
    {
      "module": "SKILL_CATALOG",
      "displayName": "Skill Catalog",
      "enabled": true,
      "required": false,
      "estimatedTokens": 8500,
      "actualRatio": 0.066,
      "configuredMaxRatio": 0.15,
      "priority": 90,
      "hitBudget": false
    },
    {
      "module": "WIKI_CONTEXT",
      "displayName": "Wiki 上下文",
      "enabled": false,
      "required": false,
      "estimatedTokens": 0,
      "actualRatio": 0.0,
      "configuredMaxRatio": 0.10,
      "priority": 80,
      "hitBudget": false
    },
    {
      "module": "MESSAGE_HISTORY",
      "displayName": "消息历史",
      "enabled": true,
      "required": true,
      "estimatedTokens": 23400,
      "actualRatio": 0.183,
      "configuredMaxRatio": null,
      "priority": 50,
      "hitBudget": false
    }
  ]
}
```

### 6.3 前端 UI 卡片

```
┌─────────────────────────────────────────────────────────┐
│  Prompt 预算总览                    [45,230 / 128,000]  │
│  ████████████░░░░░░░░░░░░░░░░░░░░░░░░░  35.3%          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ● Agent 系统提示    2,800 tok   必选    优先级: 100    │
│  ● Skill Catalog     8,500 tok   15%上限  优先级: 90    │
│    ████████▌                                            │
│  ○ Wiki 上下文       ── 已禁用 ──         优先级: 80    │
│  ● 工具调用纪律       1,200 tok   5%上限   优先级: 85    │
│  ● 运行时上下文       280 tok    必选      优先级: 95    │
│  ● 进度快照          450 tok    8%上限    优先级: 75    │
│  ● 对话摘要          3,200 tok   20%上限   优先级: 60    │
│    ███▎                                                 │
│  ● 工具定义          6,800 tok   必选      优先级: 100   │
│  ● 消息历史          23,400 tok  核心      优先级: 50    │
│    ████████████████████████                             │
│  ● 当前用户输入       800 tok    必选      优先级: 100   │
│                                                         │
│  [重置默认]                          [保存配置]          │
└─────────────────────────────────────────────────────────┘
```

## 7. 与现有裁剪机制的关系

```
                    现有机制                    新增配置层
                    ────────                    ──────────
ReasoningNode       硬编码拼接          →      PromptBudgetManager 统一拼装
                    无 token 统计       →      每模块 token 度量

ConversationWindow  L1 四阶段压缩       不变    L1 仍然作为消息历史的深度压缩
LoopMessageBudgeter L2 单轮裁剪         不变    L2 仍然作为 ReAct 循环内的保护

PromptLoader        classpath 加载+缓存  不变    加载逻辑不变
StructuredTruncator  JSON 边界截断       复用    BudgetManager 调用它裁剪超限模块
TokenEstimator      分段 token 估算      复用    度量统一使用 TokenEstimator
```

**关键原则**：配置层控制"给每个模块分配多少空间"，现有裁剪机制控制"模块内部怎么压缩"。两层正交，互不干扰。

## 8. 实现计划

| 阶段 | 内容 | 工作量 |
|------|------|--------|
| P0 | `PromptModule` 枚举 + `PromptSegment` 数据结构 | 0.5d |
| P0 | `PromptBudgetManager` 核心：isEnabled / calculateBudgets / assemble | 1.5d |
| P0 | `ReasoningNode` 改造：收集 segments → 调用 BudgetManager | 1d |
| P1 | DB Entity + CRUD API (`/prompt-budget`) | 1d |
| P1 | 前端 UI 卡片（token 可视化 + 开关 + 比例配置） | 1.5d |
| P2 | `live` 端点：最近一次请求的实际 token 分布 | 0.5d |
| P2 | Agent 级配置覆盖全局默认 | 0.5d |
| P3 | 预算超限降级建议（自动禁用低优先级模块） | 0.5d |

**总计**: ~7d

## 9. 风险与应对

| 风险 | 应对 |
|------|------|
| 配置误操作导致 prompt 质量下降 | 必选模块不可禁用；提供"重置默认"按钮 |
| 预算计算与实际 token 有偏差 | TokenEstimator 保守偏高 + live 端点提供实测数据 |
| Agent 级配置过多导致维护成本 | 全局默认 + Agent 覆盖的二级继承，不支持更深层级 |
| 配置变更击穿 prompt cache | 配置存储在 DB 而非 prompt 中；变更频率远低于对话频率 |
| 小上下文模型下模块互相挤压 | 优先级机制确保核心模块（M1/M9/M10）不被裁剪 |
