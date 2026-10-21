package com.pawwork.opencode.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of one library-level conversation turn: the assistant's final text plus a full account of
 * what the tool loop had to do to get there.
 *
 * <p>Produced by {@link com.pawwork.opencode.service.ChatService}, which mirrors the Android
 * client's {@code callAi()} loop (up to N rounds of model → tool calls → tool results → model).</p>
 */
public class ChatResult {

    private String sessionId;

    private String provider;

    private String model;

    /** Final assistant text (falls back to the reasoning trace when the model returned none). */
    private String reply;

    /** How many model round-trips the turn needed (1 when no tool was called). */
    private int rounds;

    /** Every tool invocation performed during this turn, in order. */
    private List<ToolExecution> toolExecutions = new ArrayList<>();

    /** Token usage summed across all rounds of the turn. */
    private Usage usage;

    /** Set when the turn was cut short (tool loop, round budget, or gateway error). */
    private String warning;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public int getRounds() {
        return rounds;
    }

    public void setRounds(int rounds) {
        this.rounds = rounds;
    }

    public List<ToolExecution> getToolExecutions() {
        return toolExecutions;
    }

    public void setToolExecutions(List<ToolExecution> toolExecutions) {
        this.toolExecutions = toolExecutions;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public String getWarning() {
        return warning;
    }

    public void setWarning(String warning) {
        this.warning = warning;
    }

    public boolean hasToolActivity() {
        return toolExecutions != null && !toolExecutions.isEmpty();
    }
}
