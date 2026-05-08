package vip.mate.trigger.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import vip.mate.trigger.dispatch.TriggerDispatcher;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.model.TriggerEventEntity;
import vip.mate.trigger.repository.TriggerEventMapper;
import vip.mate.trigger.repository.TriggerMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Single ingress for every event-driven trigger. Runs the four-stage filter
 * the design committee picked for v0:
 *
 * <ol>
 *   <li>Look up enabled triggers in the workspace whose {@code patternType}
 *       matches the envelope. Triggers in disabled workspaces, soft-deleted
 *       triggers, and triggers exhausted on {@code max_fires} are skipped.</li>
 *   <li>Bot-self filter — drop events whose sender matches a registered
 *       bot identity, even if the trigger config has it disabled, because
 *       a runaway echo from our own outbound traffic is the worst-case
 *       failure and not worth a per-trigger opt-out.</li>
 *   <li>Dedup window — insert a {@code mate_trigger_event} row keyed on
 *       {@code (trigger_id, dedup_key)} where the dedup key is the envelope
 *       eventId or a SHA-256 of the payload data when the upstream channel
 *       did not provide a stable id. A duplicate-key error short-circuits
 *       the dispatch silently.</li>
 *   <li>Sliding-window rate limit — per-trigger 60s cap; an over-cap event
 *       is logged and dropped without dispatching.</li>
 * </ol>
 *
 * <p>Each accepted event is then handed to {@link TriggerDispatcher} which
 * runs the workflow synchronously. v0 does not queue dispatches; if the
 * sender's webhook holds the connection open, the trigger runs in the
 * caller's thread.
 */
@Slf4j
@Service
public class TriggerEventIngestService {

    private final TriggerMapper triggerMapper;
    private final TriggerEventMapper eventMapper;
    private final TriggerDispatcher dispatcher;
    private final BotSelfFilter botSelfFilter;
    private final ObjectMapper objectMapper;
    private final TriggerPatternMatcher patternMatcher;
    private final TriggerRateLimiter rateLimiter = new TriggerRateLimiter();

    public TriggerEventIngestService(TriggerMapper triggerMapper,
                                     TriggerEventMapper eventMapper,
                                     TriggerDispatcher dispatcher,
                                     BotSelfFilter botSelfFilter,
                                     ObjectMapper objectMapper,
                                     TriggerPatternMatcher patternMatcher) {
        this.triggerMapper = triggerMapper;
        this.eventMapper = eventMapper;
        this.dispatcher = dispatcher;
        this.botSelfFilter = botSelfFilter;
        this.objectMapper = objectMapper;
        this.patternMatcher = patternMatcher;
    }

    /**
     * Process one envelope through the pipeline. Returns a result per
     * candidate trigger so callers can surface a partial-accept summary.
     */
    public List<IngestResult> ingest(TriggerEventEnvelope envelope) {
        if (envelope.patternType() == null || envelope.patternType().isBlank()) {
            return List.of();
        }
        List<TriggerEntity> candidates = triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getWorkspaceId, envelope.workspaceId())
                .eq(TriggerEntity::getPatternType, envelope.patternType())
                .eq(TriggerEntity::getEnabled, true)
                .eq(TriggerEntity::getDeleted, 0));
        if (candidates.isEmpty()) return List.of();

        List<IngestResult> results = new ArrayList<>(candidates.size());
        for (TriggerEntity trigger : candidates) {
            results.add(processSingle(trigger, envelope));
        }
        return results;
    }

    private IngestResult processSingle(TriggerEntity trigger, TriggerEventEnvelope envelope) {
        // Pattern matching is the first gate — without it, every channel
        // event would broadcast to every channel-message trigger in the
        // workspace, which is exactly the storm hazard the design forbade.
        // Run it before all the other filters so a non-matching trigger
        // doesn't even allocate a dedup row.
        if (!patternMatcher.matches(trigger, envelope)) {
            return IngestResult.dropped(trigger.getId(), Reason.PATTERN_MISMATCH);
        }
        if (Boolean.TRUE.equals(trigger.getBotSelfFilter())
                && botSelfFilter.isBotSelf(envelope.workspaceId(), envelope.senderId())) {
            return IngestResult.dropped(trigger.getId(), Reason.BOT_SELF);
        }
        if (trigger.getMaxFires() != null && trigger.getMaxFires() > 0
                && trigger.getFireCount() != null && trigger.getFireCount() >= trigger.getMaxFires()) {
            return IngestResult.dropped(trigger.getId(), Reason.EXHAUSTED);
        }
        if (!recordDedupRow(trigger, envelope)) {
            return IngestResult.dropped(trigger.getId(), Reason.DUPLICATE);
        }
        int limit = trigger.getRateLimitPerMin() == null ? 0 : trigger.getRateLimitPerMin();
        if (!rateLimiter.tryAcquire(trigger.getId(), limit, Instant.now())) {
            return IngestResult.dropped(trigger.getId(), Reason.RATE_LIMITED);
        }
        try {
            dispatcher.dispatch(trigger, envelope.data());
        } catch (Exception e) {
            log.error("Trigger {} dispatch threw on event ingest: {}",
                    trigger.getId(), e.getMessage(), e);
            return IngestResult.dropped(trigger.getId(), Reason.DISPATCH_ERROR);
        }
        return IngestResult.fired(trigger.getId());
    }

    private boolean recordDedupRow(TriggerEntity trigger, TriggerEventEnvelope envelope) {
        TriggerEventEntity row = new TriggerEventEntity();
        row.setTriggerId(trigger.getId());
        row.setDedupKey(resolveDedupKey(envelope));
        int windowSecs = trigger.getDedupWindowSecs() == null ? 60 : trigger.getDedupWindowSecs();
        Instant now = Instant.now();
        row.setReceivedAt(LocalDateTime.ofInstant(now, ZoneOffset.systemDefault()));
        row.setExpiresAt(LocalDateTime.ofInstant(now.plusSeconds(windowSecs),
                ZoneOffset.systemDefault()));
        try {
            eventMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            // Within the dedup window — silently drop.
            return false;
        }
    }

    private String resolveDedupKey(TriggerEventEnvelope envelope) {
        if (envelope.eventId() != null && !envelope.eventId().isBlank()) {
            return truncate(envelope.eventId());
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(envelope.data());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(body));
        } catch (Exception e) {
            // Fall back to a per-call random so we never hard-fail ingest.
            return "rand:" + java.util.UUID.randomUUID();
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        // Column is VARCHAR(128) — keep some headroom for trigger-prefixed keys.
        return s.length() <= 120 ? s : s.substring(0, 120);
    }

    /** Cleanup tick for expired dedup rows. Run from a scheduler in production. */
    public int sweepExpired() {
        return eventMapper.delete(new LambdaQueryWrapper<TriggerEventEntity>()
                .lt(TriggerEventEntity::getExpiresAt,
                        LocalDateTime.ofInstant(Instant.now(), ZoneOffset.systemDefault())));
    }

    public enum Reason {
        PATTERN_MISMATCH, BOT_SELF, DUPLICATE, RATE_LIMITED, EXHAUSTED, DISPATCH_ERROR
    }

    public record IngestResult(long triggerId, boolean fired, Reason droppedReason) {
        public static IngestResult fired(long triggerId) {
            return new IngestResult(triggerId, true, null);
        }
        public static IngestResult dropped(long triggerId, Reason r) {
            return new IngestResult(triggerId, false, r);
        }
    }
}
