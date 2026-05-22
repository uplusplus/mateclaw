package vip.mate.tool.builtin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Tracks Agent delegation call context to prevent infinite recursion and carry parent session info.
 * <p>
 * Uses a ThreadLocal stack so that nested delegations correctly restore the previous layer's
 * parentConversationId and childDeniedTools on exit.
 * Each {@link DelegateAgentTool} delegation calls enter() before and exit() after execution.
 *
 * @author MateClaw Team
 */
public final class DelegationContext {

    /**
     * Snapshot of one delegation layer's state.
     *
     * <p>{@code rootConversationId} is the human-facing conversation at the top
     * of the delegation tree — every layer carries it unchanged so that a
     * grandchild's progress events can be broadcast to the same stream the user
     * is watching, rather than to its immediate (machine-only) parent.
     * {@code currentSubagentId} is the id of the subagent running THIS layer; a
     * deeper child reads it as its own {@code parentSubagentId} to reconstruct
     * the spawn tree.
     */
    private record Frame(String parentConversationId, Set<String> childDeniedTools,
                         String rootConversationId, String currentSubagentId) {}

    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private DelegationContext() {}

    /** Current delegation depth (0 = top-level call, not inside any delegation) */
    public static int currentDepth() {
        return STACK.get().size();
    }

    /** Parent conversation ID for event relay (from the current frame) */
    public static String parentConversationId() {
        Frame top = STACK.get().peek();
        return top != null ? top.parentConversationId : null;
    }

    /** Denied tools set for the child Agent (from the current frame) */
    public static Set<String> childDeniedTools() {
        Frame top = STACK.get().peek();
        return top != null && top.childDeniedTools != null ? top.childDeniedTools : Set.of();
    }

    /** Root (human-facing) conversation ID for the whole tree, or null at top level. */
    public static String rootConversationId() {
        Frame top = STACK.get().peek();
        return top != null ? top.rootConversationId : null;
    }

    /** Subagent id of the layer currently executing, or null at top level. */
    public static String currentSubagentId() {
        Frame top = STACK.get().peek();
        return top != null ? top.currentSubagentId : null;
    }

    /** Enter the next delegation layer (with parent conversation ID and child tool restrictions) */
    public static void enter(String parentConversationId, Set<String> deniedTools) {
        enter(parentConversationId, deniedTools, null, null);
    }

    /**
     * Enter the next delegation layer carrying the full tree identity so deeper
     * children can broadcast to the root conversation and tag their parent.
     */
    public static void enter(String parentConversationId, Set<String> deniedTools,
                             String rootConversationId, String currentSubagentId) {
        STACK.get().push(new Frame(parentConversationId, deniedTools,
                rootConversationId, currentSubagentId));
    }

    /** Enter the next delegation layer (backward-compatible overload) */
    public static void enter() {
        enter(null, null, null, null);
    }

    /** Exit the current delegation layer, restoring the previous layer's context */
    public static void exit() {
        Deque<Frame> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        // Clean up ThreadLocal entirely when the stack is empty to prevent memory leaks
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }
}
