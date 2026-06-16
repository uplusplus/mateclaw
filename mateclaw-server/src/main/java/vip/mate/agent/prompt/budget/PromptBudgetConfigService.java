package vip.mate.agent.prompt.budget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.agent.prompt.budget.entity.PromptBudgetConfigEntity;
import vip.mate.agent.prompt.budget.repository.PromptBudgetConfigRepository;

import java.util.Optional;

/**
 * Prompt 预算配置服务 — 解析 Agent 级 + 全局默认配置。
 * <p>
 * 配置继承优先级：Agent 级 > 全局默认 > 枚举硬编码默认值。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptBudgetConfigService {

    private final PromptBudgetConfigRepository repository;

    /**
     * 解析最终生效的配置。
     * 优先级：Agent 级 > 全局 > 枚举默认值。
     */
    public PromptBudgetManager.ModuleConfig resolve(PromptModule module, Long agentId) {
        // 1. 尝试 Agent 级
        if (agentId != null) {
            Optional<PromptBudgetConfigEntity> agentCfg =
                    repository.findByAgentIdAndModule(agentId, module.name());
            if (agentCfg.isPresent()) {
                return toModuleConfig(module, agentCfg.get());
            }
        }

        // 2. 尝试全局
        Optional<PromptBudgetConfigEntity> globalCfg =
                repository.findByAgentIdIsNullAndModule(module.name());
        if (globalCfg.isPresent()) {
            return toModuleConfig(module, globalCfg.get());
        }

        // 3. 枚举默认值
        return new PromptBudgetManager.ModuleConfig(
                module,
                module.defaultEnabled(),
                module.defaultMaxRatio(),
                null,
                module.defaultPriority()
        );
    }

    /**
     * 更新 Agent 级配置
     */
    public void updateAgent(Long agentId, PromptModule module, Boolean enabled,
                             Double maxRatio, Integer maxTokens, Integer priority) {
        PromptBudgetConfigEntity entity = repository
                .findByAgentIdAndModule(agentId, module.name())
                .orElseGet(() -> {
                    PromptBudgetConfigEntity e = new PromptBudgetConfigEntity();
                    e.setAgentId(agentId);
                    e.setModule(module.name());
                    return e;
                });

        if (enabled != null) entity.setEnabled(enabled);
        if (maxRatio != null) entity.setMaxRatio(maxRatio);
        if (maxTokens != null) entity.setMaxTokens(maxTokens);
        if (priority != null) entity.setPriority(priority);

        repository.save(entity);
        log.info("[PromptBudget] 更新 Agent {} 模块 {} 配置", agentId, module.displayName());
    }

    /**
     * 更新全局默认配置
     */
    public void updateGlobal(PromptModule module, Boolean enabled,
                              Double maxRatio, Integer maxTokens, Integer priority) {
        PromptBudgetConfigEntity entity = repository
                .findByAgentIdIsNullAndModule(module.name())
                .orElseGet(() -> {
                    PromptBudgetConfigEntity e = new PromptBudgetConfigEntity();
                    e.setAgentId(null);
                    e.setModule(module.name());
                    return e;
                });

        if (enabled != null) entity.setEnabled(enabled);
        if (maxRatio != null) entity.setMaxRatio(maxRatio);
        if (maxTokens != null) entity.setMaxTokens(maxTokens);
        if (priority != null) entity.setPriority(priority);

        repository.save(entity);
        log.info("[PromptBudget] 更新全局模块 {} 配置", module.displayName());
    }

    /**
     * 重置 Agent 配置（删除所有 Agent 级覆盖，回退到全局默认）
     */
    public void resetAgent(Long agentId) {
        repository.deleteByAgentId(agentId);
        log.info("[PromptBudget] 重置 Agent {} 配置", agentId);
    }

    // ======================================================================
    // 转换
    // ======================================================================

    private PromptBudgetManager.ModuleConfig toModuleConfig(PromptModule module,
                                                              PromptBudgetConfigEntity entity) {
        return new PromptBudgetManager.ModuleConfig(
                module,
                entity.isEnabled(),
                entity.getMaxRatio(),
                entity.getMaxTokens(),
                entity.getPriority()
        );
    }
}
