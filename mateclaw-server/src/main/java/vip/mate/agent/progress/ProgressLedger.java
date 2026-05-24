package vip.mate.agent.progress;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view over a conversation's progress entries with a renderer that
 * turns the map into a compact markdown snapshot for system-prompt injection.
 *
 * <p>The snapshot is grouped by status (done → in-progress → pending →
 * blocked) and stays short on purpose: the agent reads it on every turn, so
 * spending more than ~200 tokens on it would defeat the very context
 * pressure this ledger exists to relieve.
 */
public final class ProgressLedger {

    /** Hard cap on the snapshot's note suffix so a rambling note can't bloat every turn. */
    private static final int NOTE_PREVIEW_CHARS = 120;

    private final Map<String, ProgressEntry> entries;

    public ProgressLedger(Map<String, ProgressEntry> entries) {
        this.entries = entries != null ? entries : new LinkedHashMap<>();
    }

    public static ProgressLedger empty() {
        return new ProgressLedger(new LinkedHashMap<>());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public Map<String, ProgressEntry> asMap() {
        return entries;
    }

    /**
     * @return a compact, model-readable progress snapshot, or {@code null}
     *         when the ledger is empty so the caller can skip injection
     *         entirely (no "(empty)" placeholder noise).
     */
    public String renderSnapshot() {
        if (entries.isEmpty()) {
            return null;
        }
        List<ProgressEntry> done = bucket(ProgressStatus.DONE);
        List<ProgressEntry> inProgress = bucket(ProgressStatus.IN_PROGRESS);
        List<ProgressEntry> pending = bucket(ProgressStatus.PENDING);
        List<ProgressEntry> blocked = bucket(ProgressStatus.BLOCKED);

        StringBuilder sb = new StringBuilder(256);
        sb.append("## 当前任务进度（执行参考，权威记录）\n\n");
        appendBucket(sb, "✅ 已完成", done);
        appendBucket(sb, "🔄 进行中", inProgress);
        appendBucket(sb, "⏳ 待办", pending);
        appendBucket(sb, "⛔ 受阻", blocked);
        sb.append("\n请基于此进度继续推进；已完成的步骤不要重复执行。")
                .append("完成新步骤后调用 `progress_update` 工具更新本账本。");
        return sb.toString();
    }

    private List<ProgressEntry> bucket(ProgressStatus status) {
        List<ProgressEntry> out = new ArrayList<>();
        for (ProgressEntry e : entries.values()) {
            if (e.getStatus() == status) {
                out.add(e);
            }
        }
        return out;
    }

    private void appendBucket(StringBuilder sb, String header, Collection<ProgressEntry> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(header).append(" (").append(items.size()).append("):\n");
        for (ProgressEntry e : items) {
            String label = e.getLabel() != null && !e.getLabel().isBlank() ? e.getLabel() : e.getKey();
            sb.append("- ").append(label).append(" [`").append(e.getKey()).append("`]");
            String note = e.getNote();
            if (note != null && !note.isBlank()) {
                String trimmed = note.length() > NOTE_PREVIEW_CHARS
                        ? note.substring(0, NOTE_PREVIEW_CHARS) + "…"
                        : note;
                sb.append(" — ").append(trimmed);
            }
            sb.append('\n');
        }
        sb.append('\n');
    }
}
