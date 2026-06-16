package vip.mate.agent.prompt.budget.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 预算配置实体
 */
@Data
@Entity
@Table(name = "mate_prompt_budget_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "module"}))
public class PromptBudgetConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的 Agent ID，null 表示全局默认 */
    @Column(name = "agent_id")
    private Long agentId;

    /** 模块标识符（PromptModule 枚举名） */
    @Column(nullable = false, length = 32)
    private String module;

    /** 是否启用 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 预算占比上限 (0.0 ~ 1.0) */
    @Column(name = "max_ratio")
    private Double maxRatio;

    /** 绝对 token 上限（优先于 maxRatio） */
    @Column(name = "max_tokens")
    private Integer maxTokens;

    /** 优先级，数字越大越优先保留 */
    @Column(nullable = false)
    private int priority = 50;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
