package com.pawwork.opencode.demo.dto;

import com.pawwork.opencode.model.ToolExecution;
import com.pawwork.opencode.model.Usage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response of the library-level chat endpoints: the final answer plus everything the tool loop did
 * to produce it.
 */
@Schema(name = "ChatApiReply", description = "The model's final answer for one turn.")
public class ChatApiReply {

    @Schema(description = "Send this back as sessionId on the next turn.", example = "sess_9f2c4ab1de77aa10b3c4")
    private String sessionId;

    @Schema(example = "zen")
    private String provider;

    @Schema(example = "big-pickle")
    private String model;

    @Schema(description = "The assistant's final answer.", example = "A week has 604800 seconds.")
    private String reply;

    @Schema(description = "Model round-trips this turn needed (1 when no tool ran).", example = "2")
    private int rounds;

    @Schema(description = "Every tool the model called, in order.")
    private List<ToolExecution> toolExecutions;

    @Schema(description = "Token usage summed across all rounds of the turn.")
    private Usage usage;

    @Schema(description = "Present when the turn was cut short (tool loop, round budget).", nullable = true)
    private String warning;

    @Schema(description = "Messages currently stored for this session.", example = "6")
    private int historySize;

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

    public int getHistorySize() {
        return historySize;
    }

    public void setHistorySize(int historySize) {
        this.historySize = historySize;
    }
}
