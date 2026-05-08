package vip.mate.trigger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.repository.TriggerMapper;
import vip.mate.trigger.scheduler.TriggerScheduler;

import java.util.List;
import java.util.Objects;

/**
 * CRUD facade for {@code mate_trigger} that keeps the in-memory cron
 * registration in sync with the persisted row. Pattern_version is the
 * lamport counter the scheduler uses to invalidate stale schedules across
 * a multi-node deployment — every change to {@code patternJson},
 * {@code patternType}, or the disabled→enabled transition bumps it.
 *
 * <p>The service intentionally does not wrap reads in transactions; only
 * mutating paths are {@code @Transactional} so the scheduler hand-off
 * (which reads the row again under its own connection) sees committed
 * data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerService {

    private final TriggerMapper triggerMapper;
    private final TriggerScheduler scheduler;

    public List<TriggerEntity> listByWorkspace(long workspaceId) {
        return triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getWorkspaceId, workspaceId)
                .orderByDesc(TriggerEntity::getCreateTime));
    }

    public TriggerEntity get(long id) {
        return triggerMapper.selectById(id);
    }

    @Transactional
    public TriggerEntity create(TriggerEntity trigger) {
        ensureDefaults(trigger);
        trigger.setPatternVersion(1L);
        trigger.setFireCount(0L);
        triggerMapper.insert(trigger);
        if (Boolean.TRUE.equals(trigger.getEnabled())) {
            scheduler.register(trigger);
        }
        return trigger;
    }

    @Transactional
    public TriggerEntity update(TriggerEntity updated) {
        TriggerEntity existing = triggerMapper.selectById(updated.getId());
        if (existing == null) {
            throw new IllegalArgumentException("trigger not found: " + updated.getId());
        }

        // Bump pattern_version whenever ANY field that changes the
        // schedule's behavior, payload rendering, or rate decisions
        // changes. This is the lamport other instances rely on at fire
        // time to decide whether their captured registration is stale —
        // missing a field here means a peer fires the new payload with
        // the old throttling settings (or vice versa) until it next
        // self-cancels for some other reason.
        boolean patternChanged = !Objects.equals(existing.getPatternJson(), updated.getPatternJson())
                || !Objects.equals(existing.getPatternType(), updated.getPatternType());
        boolean payloadChanged = !Objects.equals(existing.getPayloadTemplate(), updated.getPayloadTemplate());
        boolean targetChanged = !Objects.equals(existing.getTargetType(), updated.getTargetType())
                || !Objects.equals(existing.getTargetId(), updated.getTargetId());
        boolean fireConfigChanged = !Objects.equals(existing.getRateLimitPerMin(), updated.getRateLimitPerMin())
                || !Objects.equals(existing.getDedupWindowSecs(), updated.getDedupWindowSecs())
                || !Objects.equals(existing.getMaxFires(), updated.getMaxFires())
                || !Objects.equals(existing.getBotSelfFilter(), updated.getBotSelfFilter());
        boolean enableTransition = !Objects.equals(existing.getEnabled(), updated.getEnabled());

        if (patternChanged || payloadChanged || targetChanged || fireConfigChanged || enableTransition) {
            long bumped = (existing.getPatternVersion() == null ? 1L : existing.getPatternVersion()) + 1L;
            updated.setPatternVersion(bumped);
        } else {
            updated.setPatternVersion(existing.getPatternVersion());
        }
        // Preserve fireCount / lastFiredAt — those are scheduler-owned.
        updated.setFireCount(existing.getFireCount());
        updated.setLastFiredAt(existing.getLastFiredAt());

        triggerMapper.updateById(updated);

        if (Boolean.TRUE.equals(updated.getEnabled())) {
            scheduler.register(updated);
        } else {
            scheduler.unregister(updated.getId());
        }
        return updated;
    }

    @Transactional
    public void delete(long id) {
        scheduler.unregister(id);
        triggerMapper.deleteById(id);
    }

    private static void ensureDefaults(TriggerEntity t) {
        if (t.getRateLimitPerMin() == null) t.setRateLimitPerMin(60);
        if (t.getDedupWindowSecs() == null) t.setDedupWindowSecs(60);
        if (t.getBotSelfFilter() == null) t.setBotSelfFilter(true);
        if (t.getEnabled() == null) t.setEnabled(true);
        if (t.getMaxFires() == null) t.setMaxFires(0L);
    }
}
