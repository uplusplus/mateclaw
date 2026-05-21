package vip.mate.goal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalEventEntity;
import vip.mate.goal.model.GoalEventType;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.GoalUpdateRequest;
import vip.mate.goal.repository.GoalEventMapper;
import vip.mate.goal.repository.GoalMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Default implementation. Concurrency safety relies on:
 * <ol>
 *   <li>Service-level pre-check + DB-level unique index for "at most one
 *       active goal per conversation" — see V120 migration.</li>
 *   <li>Per-write optimistic lock via {@code WHERE version=?} on
 *       state-mutating updates; retried up to 3 times on contention.</li>
 *   <li>{@code @Transactional} on every write so the goal row update and
 *       the matching event-log insert succeed or fail together.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalServiceImpl implements GoalService {

    private static final int OPTIMISTIC_LOCK_MAX_RETRIES = 3;

    private final GoalMapper goalMapper;
    private final GoalEventMapper eventMapper;
    private final GoalProperties properties;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    // ==================== CRUD ====================

    @Override
    @Transactional
    public GoalEntity create(GoalCreateRequest req, String username) {
        validateCreate(req);

        // Service-level pre-check is a UX nicety only — the DB unique
        // index is the source of truth. Two concurrent creates can both
        // pass this check; the second insert will surface a
        // DuplicateKeyException and we map it to 409.
        GoalEntity active = findActiveByConversation(req.getConversationId());
        if (active != null) {
            throw new MateClawException("err.goal.conversation_has_active", 409,
                    "Conversation already has an active goal: " + active.getId());
        }

        GoalEntity entity = new GoalEntity();
        entity.setConversationId(req.getConversationId());
        entity.setAgentId(req.getAgentId());
        entity.setWorkspaceId(req.getWorkspaceId());
        entity.setCreatedBy(username);
        entity.setTitle(req.getTitle().trim());
        entity.setDescription(req.getDescription() != null ? req.getDescription() : "");
        entity.setExitCriteria(req.getExitCriteria());
        entity.setSuccessCheckPrompt(req.getSuccessCheckPrompt());
        entity.setStatus(GoalStatus.ACTIVE);
        entity.setTurnBudget(req.getTurnBudget() != null
                ? req.getTurnBudget() : properties.getDefaultTurnBudget());
        entity.setTurnsUsed(0);
        entity.setLlmCallBudget(req.getLlmCallBudget() != null
                ? req.getLlmCallBudget() : properties.getDefaultLlmCallBudget());
        entity.setAgentLlmCallsUsed(0);
        entity.setEvalLlmCallsUsed(0);
        entity.setAutoFollowupEnabled(Boolean.TRUE.equals(req.getAutoFollowupEnabled()));
        entity.setFollowupCooldownSeconds(req.getFollowupCooldownSeconds() != null
                ? req.getFollowupCooldownSeconds() : properties.getAutoFollowupCooldownSeconds());
        entity.setVersion(0);
        entity.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);

        try {
            goalMapper.insert(entity);
        } catch (DuplicateKeyException dup) {
            // Lost the race against another create. The unique index hit
            // is the authoritative "conversation already has active goal".
            throw new MateClawException("err.goal.conversation_has_active", 409,
                    "Conversation already has an active goal (DB unique index)");
        }

        writeEvent(entity.getId(), GoalEventType.CREATED, null, Map.of(
                "title", entity.getTitle(),
                "turnBudget", entity.getTurnBudget(),
                "llmCallBudget", entity.getLlmCallBudget(),
                "by", username));
        recordAudit("goal.created", entity, Map.of("by", username, "title", entity.getTitle()));
        return entity;
    }

    @Override
    public GoalEntity getById(Long id) {
        GoalEntity g = goalMapper.selectById(id);
        if (g == null) {
            throw new MateClawException("err.goal.not_found", 404, "Goal not found: " + id);
        }
        return g;
    }

    @Override
    public GoalEntity findActiveByConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return goalMapper.selectOne(new LambdaQueryWrapper<GoalEntity>()
                .eq(GoalEntity::getConversationId, conversationId)
                .eq(GoalEntity::getStatus, GoalStatus.ACTIVE)
                .last("LIMIT 1"));
    }

    @Override
    public List<GoalEntity> list(String status, String username, int limit) {
        LambdaQueryWrapper<GoalEntity> w = new LambdaQueryWrapper<GoalEntity>()
                .orderByDesc(GoalEntity::getCreateTime);
        if (username != null && !username.isBlank()) {
            w.eq(GoalEntity::getCreatedBy, username);
        }
        if (status != null && !status.isBlank()) {
            GoalStatus s;
            try {
                s = GoalStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new MateClawException("err.goal.bad_status", 400, "Unknown status: " + status);
            }
            w.eq(GoalEntity::getStatus, s);
        }
        w.last("LIMIT " + Math.max(1, Math.min(200, limit)));
        return goalMapper.selectList(w);
    }

    @Override
    @Transactional
    public GoalEntity update(Long id, GoalUpdateRequest req, String username) {
        GoalEntity g = getById(id);
        ensureNotTerminal(g, "update");

        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g);
        boolean changed = false;
        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            w.set(GoalEntity::getTitle, req.getTitle().trim()); changed = true;
        }
        if (req.getDescription() != null) {
            w.set(GoalEntity::getDescription, req.getDescription()); changed = true;
        }
        if (req.getExitCriteria() != null) {
            w.set(GoalEntity::getExitCriteria, req.getExitCriteria()); changed = true;
        }
        if (req.getSuccessCheckPrompt() != null) {
            w.set(GoalEntity::getSuccessCheckPrompt, req.getSuccessCheckPrompt()); changed = true;
        }
        if (req.getTurnBudget() != null) {
            validateBudget(req.getTurnBudget(), "turnBudget");
            w.set(GoalEntity::getTurnBudget, req.getTurnBudget()); changed = true;
        }
        if (req.getLlmCallBudget() != null) {
            validateBudget(req.getLlmCallBudget(), "llmCallBudget");
            w.set(GoalEntity::getLlmCallBudget, req.getLlmCallBudget()); changed = true;
        }
        if (req.getAutoFollowupEnabled() != null) {
            w.set(GoalEntity::getAutoFollowupEnabled, req.getAutoFollowupEnabled()); changed = true;
        }
        if (req.getFollowupCooldownSeconds() != null) {
            w.set(GoalEntity::getFollowupCooldownSeconds, req.getFollowupCooldownSeconds());
            changed = true;
        }
        if (!changed) {
            return g;
        }
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), "update");
        recordAudit("goal.updated", g, Map.of("by", username));
        return goalMapper.selectById(id);
    }

    @Override
    public List<GoalEventEntity> listEvents(Long goalId, int limit) {
        return eventMapper.selectList(new LambdaQueryWrapper<GoalEventEntity>()
                .eq(GoalEventEntity::getGoalId, goalId)
                .orderByDesc(GoalEventEntity::getId)
                .last("LIMIT " + Math.max(1, Math.min(500, limit))));
    }

    // ==================== State machine ====================

    @Override
    @Transactional
    public GoalEntity pause(Long id, String username) {
        return flipStatus(id, GoalStatus.ACTIVE, GoalStatus.PAUSED,
                GoalEventType.PAUSED, "goal.paused", username);
    }

    @Override
    @Transactional
    public GoalEntity resume(Long id, String username) {
        return flipStatus(id, GoalStatus.PAUSED, GoalStatus.ACTIVE,
                GoalEventType.RESUMED, "goal.resumed", username);
    }

    @Override
    @Transactional
    public GoalEntity abandon(Long id, String username) {
        GoalEntity g = getById(id);
        ensureNotTerminal(g, "abandon");
        // Allows abandon from both ACTIVE and PAUSED.
        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g)
                .set(GoalEntity::getStatus, GoalStatus.ABANDONED);
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), "abandon");
        writeEvent(id, GoalEventType.ABANDONED, null, Map.of("by", username));
        recordAudit("goal.abandoned", g, Map.of("by", username));
        return goalMapper.selectById(id);
    }

    @Override
    @Transactional
    public GoalEntity markCompleted(Long id, GoalEvaluationResult result) {
        GoalEntity g = getById(id);
        if (g.getStatus().isTerminal()) return g; // idempotent
        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g)
                .set(GoalEntity::getStatus, GoalStatus.COMPLETED);
        if (result != null) {
            w.set(GoalEntity::getCompletionScore, result.score())
             .set(GoalEntity::getProgressSummary, result.gap());
        }
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), "markCompleted");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("finalScore", result != null ? result.score() : null);
        detail.put("agentLlmCallsUsed", g.getAgentLlmCallsUsed());
        detail.put("evalLlmCallsUsed", g.getEvalLlmCallsUsed());
        writeEvent(id, GoalEventType.COMPLETED, null, detail);
        recordAudit("goal.completed", g, detail);
        return goalMapper.selectById(id);
    }

    @Override
    @Transactional
    public GoalEntity markExhausted(Long id, String reason) {
        GoalEntity g = getById(id);
        if (g.getStatus().isTerminal()) return g;
        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g)
                .set(GoalEntity::getStatus, GoalStatus.EXHAUSTED);
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), "markExhausted");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason != null ? reason : "unknown");
        detail.put("turnsUsed", g.getTurnsUsed());
        detail.put("agentLlmCallsUsed", g.getAgentLlmCallsUsed());
        detail.put("evalLlmCallsUsed", g.getEvalLlmCallsUsed());
        writeEvent(id, GoalEventType.EXHAUSTED, null, detail);
        recordAudit("goal.exhausted", g, detail);
        return goalMapper.selectById(id);
    }

    // ==================== Evaluation bookkeeping ====================

    @Override
    @Transactional
    public void recordEvaluation(Long id, GoalEvaluationResult result,
                                 int agentLlmCallsDelta, int evalLlmCallsDelta) {
        GoalEntity g = getById(id);
        if (g.getStatus().isTerminal()) return; // ignore late evaluations

        int agentDelta = Math.max(0, agentLlmCallsDelta);
        int evalDelta = Math.max(0, evalLlmCallsDelta);

        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g)
                .setSql("turns_used = turns_used + 1")
                .setSql("agent_llm_calls_used = agent_llm_calls_used + " + agentDelta)
                .setSql("eval_llm_calls_used = eval_llm_calls_used + " + evalDelta)
                .set(GoalEntity::getLastEvaluationAt, LocalDateTime.now());
        if (result != null) {
            w.set(GoalEntity::getCompletionScore, result.score())
             .set(GoalEntity::getProgressSummary, result.gap());
        }
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), "recordEvaluation");

        Map<String, Object> detail = new LinkedHashMap<>();
        if (result != null) {
            detail.put("completionScore", result.score());
            detail.put("gap", result.gap());
            detail.put("decision", result.decision());
            detail.put("evaluatorModel", result.evaluatorModel());
            detail.put("latencyMs", result.latencyMs());
        }
        detail.put("agentLlmCallsDelta", agentDelta);
        detail.put("evalLlmCallsDelta", evalDelta);
        writeEvent(id, GoalEventType.EVALUATED, null, detail);
    }

    @Override
    public boolean isBudgetExhausted(GoalEntity goal) {
        int turns = goal.getTurnsUsed() != null ? goal.getTurnsUsed() : 0;
        int turnBudget = goal.getTurnBudget() != null ? goal.getTurnBudget() : Integer.MAX_VALUE;
        if (turns >= turnBudget) return true;
        int callBudget = goal.getLlmCallBudget() != null ? goal.getLlmCallBudget() : Integer.MAX_VALUE;
        return goal.totalLlmCallsUsed() >= callBudget;
    }

    @Override
    public String exhaustionReason(GoalEntity goal) {
        int turns = goal.getTurnsUsed() != null ? goal.getTurnsUsed() : 0;
        int turnBudget = goal.getTurnBudget() != null ? goal.getTurnBudget() : Integer.MAX_VALUE;
        if (turns >= turnBudget) return "turn_budget";
        return "llm_call_budget";
    }

    @Override
    @Transactional
    public void recordFollowupInjected(Long id, String prompt) {
        GoalEntity g = getById(id);
        if (g.getStatus().isTerminal()) return;
        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g)
                .set(GoalEntity::getLastFollowupAt, LocalDateTime.now());
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), "recordFollowupInjected");
        writeEvent(id, GoalEventType.FOLLOWUP_INJECTED, null, Map.of(
                "prompt", prompt != null ? prompt : "",
                "turnsUsed", g.getTurnsUsed()));
    }

    @Override
    @Transactional
    public GoalEntity appendCriterion(Long id, String criterion, String username) {
        if (criterion == null || criterion.isBlank()) {
            throw new MateClawException("err.goal.criterion_empty", 400, "Criterion must not be empty");
        }
        GoalEntity g = getById(id);
        ensureNotTerminal(g, "appendCriterion");
        String existing = g.getExitCriteria() != null ? g.getExitCriteria() : "";
        String merged = existing.isEmpty() ? criterion.trim()
                : existing + "\n+ " + criterion.trim();
        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g)
                .set(GoalEntity::getExitCriteria, merged);
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), "appendCriterion");
        writeEvent(id, GoalEventType.CRITERION_ADDED, null, Map.of(
                "criterion", criterion.trim(),
                "by", username));
        return goalMapper.selectById(id);
    }

    // ==================== Internals ====================

    private void validateCreate(GoalCreateRequest req) {
        if (req == null) {
            throw new MateClawException("err.goal.bad_request", 400, "Request body required");
        }
        if (req.getConversationId() == null || req.getConversationId().isBlank()) {
            throw new MateClawException("err.goal.bad_request", 400, "conversationId required");
        }
        if (req.getAgentId() == null) {
            throw new MateClawException("err.goal.bad_request", 400, "agentId required");
        }
        if (req.getWorkspaceId() == null) {
            throw new MateClawException("err.goal.bad_request", 400, "workspaceId required");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new MateClawException("err.goal.bad_request", 400, "title required");
        }
        if (req.getTitle().length() > 255) {
            throw new MateClawException("err.goal.bad_request", 400, "title too long (>255)");
        }
        if (req.getTurnBudget() != null) validateBudget(req.getTurnBudget(), "turnBudget");
        if (req.getLlmCallBudget() != null) validateBudget(req.getLlmCallBudget(), "llmCallBudget");
    }

    private static void validateBudget(int v, String name) {
        if (v <= 0) {
            throw new MateClawException("err.goal.invalid_budget", 400,
                    name + " must be > 0, got " + v);
        }
    }

    private void ensureNotTerminal(GoalEntity g, String op) {
        if (g.getStatus().isTerminal()) {
            throw new MateClawException("err.goal.terminal_state", 409,
                    "Cannot " + op + " a goal in terminal state " + g.getStatus().getValue());
        }
    }

    /** Build an update wrapper that enforces version match + soft-delete guard. */
    private LambdaUpdateWrapper<GoalEntity> baseLockedUpdate(GoalEntity g) {
        return new LambdaUpdateWrapper<GoalEntity>()
                .eq(GoalEntity::getId, g.getId())
                .eq(GoalEntity::getVersion, g.getVersion())
                .eq(GoalEntity::getDeleted, 0);
    }

    private static void bumpVersionAndTime(LambdaUpdateWrapper<GoalEntity> w) {
        w.setSql("version = version + 1")
         .set(GoalEntity::getUpdateTime, LocalDateTime.now());
    }

    private void retryOptimistic(IntSupplier update, String op) {
        for (int i = 0; i < OPTIMISTIC_LOCK_MAX_RETRIES; i++) {
            int rows = update.getAsInt();
            if (rows > 0) return;
            log.debug("[GoalService] Optimistic lock miss on {} (attempt {}/{})",
                    op, i + 1, OPTIMISTIC_LOCK_MAX_RETRIES);
        }
        throw new MateClawException("err.goal.optimistic_lock_conflict", 409,
                "Concurrent modification on " + op + " after "
                        + OPTIMISTIC_LOCK_MAX_RETRIES + " retries");
    }

    private GoalEntity flipStatus(Long id, GoalStatus from, GoalStatus to,
                                  String eventType, String auditAction, String username) {
        GoalEntity g = getById(id);
        if (g.getStatus() != from) {
            throw new MateClawException("err.goal.bad_transition", 409,
                    "Cannot transition " + g.getStatus().getValue() + " -> " + to.getValue());
        }
        LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(g)
                .set(GoalEntity::getStatus, to);
        bumpVersionAndTime(w);
        retryOptimistic(() -> goalMapper.update(null, w), to.getValue());
        writeEvent(id, eventType, null, Map.of("by", username,
                "from", from.getValue(), "to", to.getValue()));
        recordAudit(auditAction, g, Map.of("by", username));
        return goalMapper.selectById(id);
    }

    private void writeEvent(Long goalId, String type, Long messageId, Map<String, Object> detail) {
        GoalEventEntity ev = new GoalEventEntity();
        ev.setGoalId(goalId);
        ev.setEventType(type);
        ev.setMessageId(messageId);
        ev.setDetailJson(safeJson(detail));
        ev.setCreateTime(LocalDateTime.now());
        eventMapper.insert(ev);
    }

    private void recordAudit(String action, GoalEntity g, Map<String, Object> detail) {
        try {
            auditEventService.record(action, "goal",
                    String.valueOf(g.getId()), g.getTitle(),
                    safeJson(detail), g.getWorkspaceId());
        } catch (Exception ex) {
            log.warn("[GoalService] audit record failed: {}", ex.getMessage());
        }
    }

    private String safeJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Returns the time elapsed since the last followup, or null if never. */
    public Duration timeSinceLastFollowup(GoalEntity g) {
        if (g.getLastFollowupAt() == null) return null;
        return Duration.between(g.getLastFollowupAt(), LocalDateTime.now());
    }
}
