-- V100: Prompt 模块化预算配置表
CREATE TABLE mate_prompt_budget_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id        BIGINT          NULL COMMENT '关联 Agent ID，NULL 表示全局默认',
    module          VARCHAR(32)     NOT NULL COMMENT '模块枚举名',
    enabled         TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    max_ratio       DECIMAL(5,4)    NULL COMMENT '预算占比上限 (0.0000~1.0000)',
    max_tokens      INT             NULL COMMENT '绝对 token 上限（优先于 max_ratio）',
    priority        INT             NOT NULL DEFAULT 50 COMMENT '优先级，数字越大越优先保留',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_agent_module (agent_id, module),
    INDEX idx_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt 模块化预算配置';

-- 全局默认数据
INSERT INTO mate_prompt_budget_config (agent_id, module, enabled, max_ratio, max_tokens, priority) VALUES
(NULL, 'AGENT_SYSTEM_PROMPT', 1, NULL,  NULL,  100),
(NULL, 'SKILL_CATALOG',       1, 0.15,  NULL,  90),
(NULL, 'WIKI_CONTEXT',        1, 0.10,  NULL,  80),
(NULL, 'TOOL_ENFORCEMENT',    1, 0.05,  NULL,  85),
(NULL, 'RUNTIME_CONTEXT',     1, NULL,  NULL,  95),
(NULL, 'PROGRESS_LEDGER',     1, 0.08,  NULL,  75),
(NULL, 'CONV_SUMMARY',        1, 0.20,  NULL,  60),
(NULL, 'TOOL_DEFINITIONS',    1, NULL,  NULL,  100),
(NULL, 'MESSAGE_HISTORY',     1, NULL,  NULL,  50),
(NULL, 'CURRENT_USER_INPUT',  1, NULL,  NULL,  100);
