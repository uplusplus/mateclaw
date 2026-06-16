# ADR-001: MateClaw Prompt 架构与上下文裁剪策略

- **状态**: 已分析
- **日期**: 2026-06-16
- **来源**: https://github.com/uplusplus/mateclaw/tree/main

## 背景

MateClaw 是一个基于 Spring AI + Alibaba StateGraph 构建的多 Agent 框架，采用 ReAct（Reasoning-Acting-Observation）循环模式与 LLM 交互。本文档分析其 Prompt 拼装架构及输入窗口不足时的裁剪策略。

## 1. Prompt 组成结构

MateClaw 发送给 LLM 的完整请求由以下 10 个部分拼装：

### 1.1 SystemMessage 部分

| 序号 | 组件 | 来源 | 说明 |
|------|------|------|------|
| ① | Agent 系统提示 | AgentEntity.systemPrompt 配置 | Agent 人格、职责定义 |
| ② | Skill Catalog | `SkillCatalogRenderer` 动态渲染 | 每轮重新生成，按加载顺序排列 |
| ③ | Wiki 上下文 | `WikiContextService` 按相关性注入 | 知识库匹配内容 |
| ④ | 工具调用纪律 | `ReasoningNode.TOOL_USE_ENFORCEMENT` 硬编码 | 禁止空承诺、强制 tool_call、进度跟踪规则 |

### 1.2 运行时注入的 UserMessage 部分

| 序号 | 组件 | 来源 | 说明 |
|------|------|------|------|
| ⑤ | 运行时上下文 | `RuntimeContextInjector` | 当前时间 (Asia/Shanghai)、工作目录、Skill 根目录、IM 渠道/发送者信息 |
| ⑥ | 进度快照 | `ProgressLedgerService` | 多步任务的 "已完成清单"，每轮更新，不受历史裁剪影响 |
| ⑦ | 历史摘要 | `ConversationWindowManager` 压缩输出 | 结构化摘要：Goal/Progress/Decisions/Files/NextSteps |

### 1.3 消息历史部分

| 序号 | 组件 | 来源 | 说明 |
|------|------|------|------|
| ⑧ | 多轮对话消息 | ConversationService | UserMessage / AssistantMessage / ToolResponseMessage 交替序列，经裁剪 |
| ⑨ | 当前用户输入 | 本轮 UserMessage | 用户最新消息 |
| ⑩ | 工具定义 | `ToolRegistry` → JSON Schema | 每次请求携带，可达几千 token |

### 1.4 设计要点

- **RuntimeContextInjector 使用 UserMessage 而非 SystemMessage**：保持 Anthropic prompt cache 命中率（SystemMessage 变化会击穿 cache）
- **Skill Catalog 每轮动态渲染**：load_skill 加载的新 skill 能立即浮到顶部，同时不破坏 prompt cache 前缀
- **Progress Ledger 独立于消息历史**：即使历史被裁剪，进度快照仍然保留

## 2. 两层裁剪架构

```
用户消息到达
    │
    ▼
┌──────────────────────────────┐
│  L1: ConversationWindowManager │  ← 每次用户 turn 开始前
│  多轮历史整体压缩               │
│  四阶段渐进式策略               │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│  L2: LoopMessageBudgeter      │  ← 每次 ReAct 循环迭代内
│  单轮内工具调用累积裁剪         │
│  保护 5 个不变量               │
└──────────────────────────────┘
```

### 2.1 L1: ConversationWindowManager（多轮压缩）

**触发条件**：

```
totalTokens = systemTokens + currentMsgTokens + historyTokens + toolsTokens
triggerThreshold = effectiveMaxInputTokens × compactTriggerRatio

if totalTokens > triggerThreshold → 触发压缩
```

**预算计算**：

```
historyBudget = effectiveMax - systemTokens - currentMsgTokens - toolsTokens - 5%安全余量
reservedTokens 硬封顶: max(1024, effectiveMax / 2)  // 防止小上下文模型死循环
tailTokenBudget = triggerThreshold × 20%
```

**四阶段渐进式压缩**：

```
Phase 1: Soft Trim（软裁剪）
  ├→ 旧 tool_response: head(4000字符) + 截断标记 + tail(1500字符)
  ├→ 最近 3 条工具结果保持原样 (KEEP_RECENT_TOOL_RESPONSES = 3)
  ├→ delegateToAgent/delegateParallel 结果豁免（子代理不可重放）
  └→ 如果裁剪后 fits → 返回

Phase 2: Hard Clear（硬清除）
  ├→ 所有旧 tool_response 替换为占位符
  └→ 如果清除后 fits → 返回

Phase 2.5: MemoryProvider 钩子
  ├→ MemoryManager.onPreCompress() 提取关键信息
  └→ 提取结果混入摘要上下文

Phase 3: Pre-Prune + LLM 结构化摘要
  ├→ Pre-Prune: 喂给摘要 LLM 前清理旧工具输出（减少输入 token）
  ├→ 调用摘要 LLM 生成结构化摘要
  │   模板: prompts/context/structured-summary-system.txt
  │         prompts/context/structured-summary-user.txt
  │         prompts/context/structured-summary-update.txt（迭代更新用）
  ├→ 摘要注入为 UserMessage（非 SystemMessage，防止注入攻击）
  ├→ 支持迭代更新：旧摘要 + 新轮次合并
  └→ 摘要失败后 10 分钟冷却 (SUMMARY_COOLDOWN_MS = 600_000)
```

**工具对安全性**：

```
enforcePairSafeBoundary():
  裁剪边界不能拆散 AssistantMessage.tool_calls ↔ ToolResponseMessage 的配对
  如果拆散 → 边界前移，直到配对完整
  如果前移后 headEnd >= tailStart → 跳过压缩（宁可超 token 也不破坏配对）
```

### 2.2 L2: LoopMessageBudgeter（单轮内裁剪）

**触发条件**：

```
promptTokens = historyTokens + reservedPrefixTokens
if promptTokens >= triggerTokens || messageCount >= targetMaxMessages → 触发裁剪
```

**5 个不变量**：

| 不变量 | 策略 |
|--------|------|
| ① SystemMessage 头部保护 | 所有连续 SystemMessage 永远保留 |
| ② Turn Anchor | 最新 UserMessage 永远不丢（pull back 或 stitch） |
| ③ 工具对完整性 | `ToolPairSanitizer` 保证 tool_call ↔ tool_response 不拆散 |
| ④ Token 预算优先 | 从尾部向前累积 token 定切点，而非按消息数 |
| ⑤ 最少尾部消息 | 至少保留 minTailMessages 条，防止巨大工具输出挤掉推理上下文 |

**裁剪算法**：

```
1. findHeadEnd(): 找到头部 SystemMessage 结束位置
2. findTailCutByTokens(): 从尾部向前累积 token，找到切点
3. minTailMessages 兜底: 如果硬切太少 → 回退到软天花板
4. Anchor 保护: 最新 UserMessage 在切点前 → pull back
5. ToolPairSanitizer: 工具对边界回退
6. targetMaxMessages 安全网: pair integrity 优先于数量上限
7. removeOrphans(): 清理跨边界的孤儿 tool_call/tool_response
```

### 2.3 L2 辅助: ReasoningNode 老工具压缩

在 L2 之前，ReasoningNode 还会对每轮内的老工具结果做预压缩：

```
只保留最近 KEEP_RECENT_TOOL_RESPONSES = 3 条 tool_response 原文
更早的替换为: "[此工具的输出已被清除以节省上下文，如需请重新调用]"
```

## 3. StructuredTruncator — JSON 边界感知截断

当截断内容看起来像 JSON（以 `{` 或 `[` 开头）时：

```
截断点 snap 到最近的结构边界:
  - , 之后
  - } 之后
  - ] 之后
  （仅限不在字符串字面量内部的位置）

snap 代价不超过预算的一半 → 否则回退到普通字符截断
```

**保真指令**（附加到截断标记后）：

> *"Do NOT infer or fabricate omitted content; retrieve the full data (e.g. read_file) or tell the user the result is incomplete."*

## 4. Token 估算策略

`TokenEstimator` 使用分段估算（保守偏高，确保压缩触发不会过晚）：

| 字符类型 | 估算比例 | 说明 |
|---------|---------|------|
| CJK（中日韩统一表意文字 + 假名/谚文） | 1 字符 ≈ 1 token | |
| ASCII | 4 字符 ≈ 1 token | |
| 其他 Unicode | 1.5 字符 ≈ 1 token | |

**额外开销**：
- 每条消息: +4 token (role 标记、分隔符)
- 每个工具定义: +12 token (JSON Schema wrapper)

## 5. 关键设计决策总结

| 决策 | 原因 |
|------|------|
| 摘要注入为 UserMessage 而非 SystemMessage | 防止历史用户输入被提升为系统级指令（安全） |
| RuntimeContextInjector 使用 UserMessage | 保持 Anthropic prompt cache 命中率 |
| 子代理结果豁免裁剪 | delegateToAgent 是不可重放的（子代理是独立 LLM session） |
| JSON 边界感知截断 | 防止半截 JSON 诱导模型编造缺失字段 |
| 摘要失败冷却 10 分钟 | 防止雪崩（摘要 LLM 失败 → 重试 → 再失败） |
| reservedTokens 封顶到 50% | 防止小上下文模型（16K/8K）死循环压缩 |
| 工具对完整性优先于 token 预算 | 拆散 tool_call ↔ tool_response 会导致 400 错误 |
| Progress Ledger 独立于消息历史 | 长任务中即使历史被裁剪，"已完成清单" 不丢 |
| Prompt 文件按 classpath 加载 + 缓存 | `PromptLoader` 线程安全懒加载，ConcurrentHashMap 缓存 |

## 6. 相关源码文件

```
vip/mate/agent/
├── prompt/
│   └── PromptLoader.java              # Prompt 文件加载器
├── context/
│   ├── ConversationWindowManager.java  # L1 多轮压缩（四阶段）
│   ├── LoopMessageBudgeter.java        # L2 单轮裁剪（5 不变量）
│   ├── StructuredTruncator.java        # JSON 边界感知截断
│   ├── RuntimeContextInjector.java     # 运行时上下文注入
│   ├── TokenEstimator.java             # Token 估算
│   ├── ToolPairSanitizer.java          # 工具对完整性保障
│   ├── LoopBudgetConfig.java           # L2 预算配置 record
│   └── ToolResultStorage.java          # 工具结果溢出存储
├── graph/
│   ├── StateGraphReActAgent.java       # ReAct Agent 主循环
│   └── node/
│       └── ReasoningNode.java          # 推理节点（组装 prompt + 调用 LLM）
└── progress/
    └── ProgressLedgerService.java      # 进度快照服务

resources/prompts/
├── context/
│   ├── structured-summary-system.txt   # 摘要系统提示
│   ├── structured-summary-user.txt     # 摘要用户提示
│   └── structured-summary-update.txt   # 迭代更新提示
├── graph/
│   ├── summarize-system.txt            # 图级摘要系统提示
│   ├── summarize-user.txt              # 图级摘要用户提示
│   ├── limit-exceeded-system.txt       # 超限提示
│   └── limit-exceeded-user.txt
├── memory/                             # 记忆相关提示
├── research/                           # 研究相关提示
├── skill/                              # 技能相关提示
└── wiki/                               # Wiki 相关提示
```
