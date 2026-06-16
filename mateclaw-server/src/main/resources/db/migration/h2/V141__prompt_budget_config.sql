-- V100: Prompt 模块化预算配置表
CREATE TABLE mate_prompt_budget_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id        BIGINT          NULL,
    module          VARCHAR(32)     NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    max_ratio       DECIMAL(5,4)    NULL,
    max_tokens      INTEGER         NULL,
    priority        INTEGER         NOT NULL DEFAULT 50,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_agent_module UNIQUE (agent_id, module)
);

CREATE INDEX idx_agent ON mate_prompt_budget_config(agent_id);

-- 全局默认数据
INSERT INTO mate_prompt_budget_config (agent_id, module, enabled, max_ratio, max_tokens, priority) VALUES
(NULL, 'AGENT_SYSTEM_PROMPT', TRUE, NULL,  NULL,  100),
(NULL, 'SKILL_CATALOG',       TRUE, 0.15,  NULL,  90),
(NULL, 'WIKI_CONTEXT',        TRUE, 0.10,  NULL,  80),
(NULL, 'TOOL_ENFORCEMENT',    TRUE, 0.05,  NULL,  85),
(NULL, 'RUNTIME_CONTEXT',     TRUE, NULL,  NULL,  95),
(NULL, 'PROGRESS_LEDGER',     TRUE, 0.08,  NULL,  75),
(NULL, 'CONV_SUMMARY',        TRUE, 0.20,  NULL,  60),
(NULL, 'TOOL_DEFINITIONS',    TRUE, NULL,  NULL,  100),
(NULL, 'MESSAGE_HISTORY',     TRUE, NULL,  NULL,  50),
(NULL, 'CURRENT_USER_INPUT',  TRUE, NULL,  NULL,  100);
