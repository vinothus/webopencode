package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Decides whether a tool call may run, and optionally waits for a human answer.
 *
 * <p>Three modes, selected from the UI and echoed per request:</p>
 * <ul>
 *   <li>{@code auto} — run everything, except tools the implementation always asks about
 *       (the shell).</li>
 *   <li>{@code ask} — pause before every tool call and wait for the user's Allow/Deny.</li>
 *   <li>{@code off} — refuse every call; the model is told it has no tools.</li>
 * </ul>
 *
 * <p>The waiting implementation belongs to the host application (it knows how to reach its UI);
 * the tool loop only depends on this interface.</p>
 */
public interface ToolApproval {

    /** What the loop should do with a proposed call. */
    enum Decision {
        /** Run it now. */
        ALLOW,
        /** Do not run it; hand the model a refusal. */
        DENY
    }

    /** The requested mode, lower-case. */
    String mode();

    /**
     * Resolves one call.
     *
     * @param toolName  the tool the model wants to run
     * @param arguments the raw JSON arguments
     * @param force     {@code true} when this tool must be confirmed even in {@code auto} mode
     * @return the decision; never {@code null}
     */
    Decision decide(String toolName, String arguments, boolean force);

    /**
     * Whether this call needs a human even when the mode is {@code auto}. Implementations use it
     * for dangerous tools (the shell) so the UI can prompt regardless of mode.
     */
    default boolean requiresConfirmation(String toolName, JsonNode arguments) {
        return false;
    }

    /** Runs everything without asking — the library default when no UI is attached. */
    static ToolApproval alwaysAllow() {
        return new ToolApproval() {
            @Override
            public String mode() {
                return "auto";
            }

            @Override
            public Decision decide(String toolName, String arguments, boolean force) {
                return Decision.ALLOW;
            }
        };
    }

    /** Convenience: is this mode one the loop understands? */
    static boolean isKnownMode(String mode) {
        return "auto".equals(mode) || "ask".equals(mode) || "off".equals(mode);
    }
}
