package vip.mate.agent.prompt.budget.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vip.mate.agent.prompt.budget.PromptBudgetManager;
import vip.mate.agent.prompt.budget.PromptBudgetConfigService;
import vip.mate.agent.prompt.budget.PromptModule;
import vip.mate.agent.prompt.budget.PromptSegment;
import vip.mate.core.common.util.R;

import java.util.*;

/**
 * Prompt 预算配置 API
 */
@Tag(name = "Prompt Budget", description = "Prompt 模块化预算配置")
@RestController
@RequestMapping("/api/agent/prompt-budget")
@RequiredArgsConstructor
public class PromptBudgetController {

    private final PromptBudgetConfigService configService;
    private final PromptBudgetManager budgetManager;

    /**
     * 获取 Agent 的 prompt 预算配置
     */
    @GetMapping("/{agentId}")
    @Operation(summary = "获取 Agent 的 prompt 预算配置")
    public R<Map<String, Object>> getConfig(@PathVariable Long agentId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);

        List<Map<String, Object>> modules = new ArrayList<>();
        for (PromptModule m : PromptModule.values()) {
            var cfg = configService.resolve(m, agentId);
            Map<String, Object> mod = new LinkedHashMap<>();
            mod.put("module", m.name());
            mod.put("displayName", m.displayName());
            mod.put("layer", m.layer().name());
            mod.put("enabled", cfg.enabled());
            mod.put("required", m.required());
            mod.put("maxRatio", cfg.maxRatio());
            mod.put("maxTokens", cfg.maxTokens());
            mod.put("priority", cfg.priority());
            mod.put("defaultEnabled", m.defaultEnabled());
            mod.put("defaultMaxRatio", m.defaultMaxRatio());
            mod.put("defaultPriority", m.defaultPriority());
            modules.add(mod);
        }
        result.put("modules", modules);
        return R.ok(result);
    }

    /**
     * 更新单个模块配置
     */
    @PutMapping("/{agentId}/module/{module}")
    @Operation(summary = "更新模块配置")
    public R<Void> updateModule(
            @PathVariable Long agentId,
            @PathVariable PromptModule module,
            @RequestBody ModuleUpdateRequest req) {
        configService.updateAgent(agentId, module,
                req.enabled, req.maxRatio, req.maxTokens, req.priority);
        return R.ok();
    }

    /**
     * 批量更新
     */
    @PutMapping("/{agentId}")
    @Operation(summary = "批量更新模块配置")
    public R<Void> updateAll(
            @PathVariable Long agentId,
            @RequestBody List<ModuleUpdateRequest> updates) {
        for (ModuleUpdateRequest req : updates) {
            if (req.module == null) continue;
            configService.updateAgent(agentId, req.module,
                    req.enabled, req.maxRatio, req.maxTokens, req.priority);
        }
        return R.ok();
    }

    /**
     * 重置为全局默认
     */
    @DeleteMapping("/{agentId}")
    @Operation(summary = "重置 Agent 配置为全局默认")
    public R<Void> reset(@PathVariable Long agentId) {
        configService.resetAgent(agentId);
        return R.ok();
    }

    /**
     * 预览各模块 token 占用
     */
    @PostMapping("/{agentId}/preview")
    @Operation(summary = "预览各模块 token 占用")
    public R<List<PromptBudgetManager.PromptModuleMetrics>> preview(
            @PathVariable Long agentId,
            @RequestBody PreviewRequest req) {
        List<PromptSegment> segments = new ArrayList<>();
        for (var entry : req.moduleContents.entrySet()) {
            segments.add(PromptSegment.of(entry.getKey(), entry.getValue()));
        }
        var metrics = budgetManager.preview(segments, req.contextWindow, agentId);
        return R.ok(metrics);
    }

    // ======================================================================
    // Request DTOs
    // ======================================================================

    public static class ModuleUpdateRequest {
        public PromptModule module;
        public Boolean enabled;
        public Double maxRatio;
        public Integer maxTokens;
        public Integer priority;
    }

    public static class PreviewRequest {
        public int contextWindow = 128000;
        public Map<PromptModule, String> moduleContents = new LinkedHashMap<>();
    }
}
