package vip.mate.agent.prompt.budget;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 预算配置服务 — 解析 Agent 级 + 全局默认配置。
 * <p>
 * 生产实现从 DB 读取 {@code mate_prompt_budget_config} 表；
 * 此处提供内存实现用于 POC 和单元测试。
 */
public class PromptBudgetConfigService {

    /**
     * 全局默认配置（agentId = null 的 key）
     * Key = "null:MODULE_NAME"
     */
    private final Map<String, PromptBudgetManager.ModuleConfig> globalConfigs = new ConcurrentHashMap<>();

    /**
     * Agent 级覆盖配置
     * Key = "agentId:MODULE_NAME"
     */
    private final Map<String, PromptBudgetManager.ModuleConfig> agentConfigs = new ConcurrentHashMap<>();

    /**
     * 解析最终生效的配置。
     * 优先级：Agent 级 > 全局 > 枚举默认值。
     */
    public PromptBudgetManager.ModuleConfig resolve(PromptModule module, Long agentId) {
        // 1. 尝试 Agent 级
        if (agentId != null) {
            String agentKey = agentId + ":" + module.name();
            PromptBudgetManager.ModuleConfig cfg = agentConfigs.get(agentKey);
            if (cfg != null) return cfg;
        }

        // 2. 尝试全局
        String globalKey = "null:" + module.name();
        PromptBudgetManager.ModuleConfig cfg = globalConfigs.get(globalKey);
        if (cfg != null) return cfg;

        // 3. 枚举默认值
        return new PromptBudgetManager.ModuleConfig(
                module,
                module.defaultEnabled(),
                module.defaultMaxRatio(),
                null,
                module.defaultPriority()
        );
    }

    // ======================================================================
    // 配置更新 API（供 Controller 调用）
    // ======================================================================

    public void updateGlobal(PromptModule module, boolean enabled,
                              Double maxRatio, Integer maxTokens, Integer priority) {
        String key = "null:" + module.name();
        int p = priority != null ? priority : module.defaultPriority();
        globalConfigs.put(key, new PromptBudgetManager.ModuleConfig(
                module, enabled, maxRatio, maxTokens, p));
    }

    public void updateAgent(Long agentId, PromptModule module, boolean enabled,
                             Double maxRatio, Integer maxTokens, Integer priority) {
        String key = agentId + ":" + module.name();
        int p = priority != null ? priority : module.defaultPriority();
        agentConfigs.put(key, new PromptBudgetManager.ModuleConfig(
                module, enabled, maxRatio, maxTokens, p));
    }

    public void resetAgent(Long agentId) {
        agentConfigs.entrySet().removeIf(e -> e.getKey().startsWith(agentId + ":"));
    }

    public void resetAll() {
        globalConfigs.clear();
        agentConfigs.clear();
    }
}
