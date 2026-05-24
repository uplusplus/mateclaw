package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.progress.ProgressLedger;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.progress.ProgressStatus;

/**
 * Tool exposed to the LLM for maintaining the conversation-scoped progress
 * ledger. The runtime renders the ledger into the system prompt before every
 * reasoning step, so the model can rely on this tool as the durable record
 * of "what I have done and what remains" across context-window trims.
 *
 * <p>Why a single mutating tool rather than separate
 * {@code progress_mark_done} / {@code progress_block} / etc. methods: the
 * model already volunteers the desired status as a string. Splitting into
 * per-status methods would multiply the tool schema for no gain and forces
 * a re-classification when statuses evolve.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgressLedgerTool {

    private final ProgressLedgerService service;

    @Tool(description = "Record or update a single step in the current conversation's progress "
            + "ledger. Use this to track multi-step tasks (research workflows, document drafting "
            + "split by section, etc.) — the runtime injects a rendered snapshot of the ledger "
            + "into your context before every reasoning step so you never lose track of what is "
            + "already done after a context trim. Call once per step transition: "
            + "register pending entries up front when you decompose a task, mark in_progress "
            + "before starting each one, then done as soon as it lands. Re-using the same stepKey "
            + "overwrites the entry in place (no duplicates).")
    public String progress_update(
            @ToolParam(description = "Stable identifier for this step (e.g. 'model_gpt55', "
                    + "'section_intro', 'step_pptx'). Reuse exactly to update an existing entry.")
            String stepKey,
            @ToolParam(description = "Human-readable label shown in the snapshot (e.g. "
                    + "'GPT-5.5 调研'). Pass empty to keep the existing label when updating.",
                    required = false)
            String label,
            @ToolParam(description = "One of: pending, in_progress, done, blocked.")
            String status,
            @ToolParam(description = "Optional 1-line note (why it's blocked, what was produced, "
                    + "next sub-step). Capped at ~120 chars when rendered into the snapshot.",
                    required = false)
            String note,
            @Nullable ToolContext ctx) {

        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId == null || conversationId.isBlank()) {
            // Happens only on test paths that bypass the executor wiring;
            // give a structured error so the LLM doesn't loop on it.
            return "Error: no conversation context bound to this call. progress_update is only "
                    + "usable from inside an active agent run.";
        }
        if (stepKey == null || stepKey.isBlank()) {
            return "Error: stepKey is required.";
        }
        ProgressStatus parsed = ProgressStatus.parse(status);
        if (parsed == null) {
            return "Error: status must be one of pending, in_progress, done, blocked. Got: " + status;
        }
        try {
            ProgressLedger updated = service.upsert(conversationId, stepKey, label, parsed, note);
            return "Recorded " + stepKey + " → " + parsed.wireValue()
                    + ". Ledger now has " + updated.size() + " entries.";
        } catch (Exception e) {
            log.warn("progress_update failed for conv={} key={}: {}", conversationId, stepKey, e.getMessage());
            return "Error: failed to persist progress entry — " + e.getMessage();
        }
    }
}
