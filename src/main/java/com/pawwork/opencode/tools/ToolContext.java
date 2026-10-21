package com.pawwork.opencode.tools;

/**
 * Per-call context for a running tool: which conversation it belongs to, and how deep a sub-agent
 * chain it sits in.
 *
 * <p>The {@link ToolHandler} interface stays untouched — a handler that needs the context calls
 * {@link #current()} and reads the session id or depth. The tool loop sets it around every call and
 * clears it afterwards, so handlers on other threads never see a stale value.</p>
 */
public final class ToolContext {

    private static final ThreadLocal<ToolContext> CURRENT = new ThreadLocal<>();

    private final String sessionId;
    private final int depth;

    private ToolContext(String sessionId, int depth) {
        this.sessionId = sessionId;
        this.depth = depth;
    }

    /** The context of the tool call running on this thread, or a blank one when called outside a loop. */
    public static ToolContext current() {
        ToolContext context = CURRENT.get();
        return context == null ? new ToolContext("", 0) : context;
    }

    /** Installs a context for the duration of one tool call. */
    public static Scope open(String sessionId, int depth) {
        ToolContext previous = CURRENT.get();
        CURRENT.set(new ToolContext(sessionId == null ? "" : sessionId, depth));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** The conversation this call belongs to; empty when there is none. */
    public String sessionId() {
        return sessionId;
    }

    /** Sub-agent nesting depth: 0 for a normal turn, higher inside a {@code task}. */
    public int depth() {
        return depth;
    }

    /** Restores the previous context. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
