package vip.mate.agent.prompt.budget.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.prompt.budget.entity.PromptBudgetConfigEntity;

import java.util.List;
import java.util.Optional;

/**
 * Prompt 预算配置 Repository
 */
public interface PromptBudgetConfigRepository extends JpaRepository<PromptBudgetConfigEntity, Long> {

    Optional<PromptBudgetConfigEntity> findByAgentIdAndModule(Long agentId, String module);

    Optional<PromptBudgetConfigEntity> findByAgentIdIsNullAndModule(String module);

    List<PromptBudgetConfigEntity> findByAgentId(Long agentId);

    List<PromptBudgetConfigEntity> findByAgentIdIsNull();

    @Modifying
    @Transactional
    void deleteByAgentId(Long agentId);
}
