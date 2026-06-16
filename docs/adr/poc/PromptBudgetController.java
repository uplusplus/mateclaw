package vip.mate.agent.prompt.budget;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Prompt 预算配置 API
 */
@Tag(name = "Prompt Budget", description = "Prompt 模块化预算配置")
@RestController
@RequestMapping("/api/agents/{agentId}/prompt-budget")
@RequiredArgsConstructor
public class PromptBudgetController {

    private final PromptBudgetConfigService configService;
    private final PromptBudgetManager budgetManager;

    /**
     * 获取当前配置 + 各模块默认值
     */
    @GetMapping
    @Operation(summary = "获取 Agent 的 prompt 预算配置")
    public ResponseEntity<Map<String, Object>> getConfig(@PathVariable Long agentId) {
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
        return ResponseEntity.ok(result);
    }

    /**
     * 更新单个模块配置
     */
    @PutMapping("/{module}")
    @Operation(summary = "更新模块配置")
    public ResponseEntity<Void> updateModule(
            @PathVariable Long agentId,
            @PathVariable PromptModule module,
            @RequestBody ModuleUpdateRequest req) {

        configService.updateAgent(agentId, module,
                req.enabled != null ? req.enabled : module.defaultEnabled(),
                req.maxRatio,
                req.maxTokens,
                req.priority);
        return ResponseEntity.ok().build();
    }

    /**
     * 批量更新
     */
    @PutMapping
    @Operation(summary = "批量更新模块配置")
    public ResponseEntity<Void> updateAll(
            @PathVariable Long agentId,
            @RequestBody List<ModuleUpdateRequest> updates) {

        for (ModuleUpdateRequest req : updates) {
            if (req.module == null) continue;
            configService.updateAgent(agentId, req.module,
                    req.enabled != null ? req.enabled : req.module.defaultEnabled(),
                    req.maxRatio,
                    req.maxTokens,
                    req.priority);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 重置为全局默认
     */
    @DeleteMapping
    @Operation(summary = "重置 Agent 配置为全局默认")
    public ResponseEntity<Void> reset(@PathVariable Long agentId) {
        configService.resetAgent(agentId);
        return ResponseEntity.ok().build();
    }

    /**
     * 预览（不实际组装，只返回度量估算）
     */
    @PostMapping("/preview")
    @Operation(summary = "预览各模块 token 占用")
    public ResponseEntity<List<PromptBudgetManager.PromptModuleMetrics>> preview(
            @PathVariable Long agentId,
            @RequestBody PreviewRequest req) {

        List<PromptSegment> segments = new ArrayList<>();
        for (var entry : req.moduleContents.entrySet()) {
            segments.add(PromptSegment.of(entry.getKey(), entry.getValue()));
        }

        var metrics = budgetManager.preview(segments, req.contextWindow, agentId);
        return ResponseEntity.ok(metrics);
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
