package com.pawwork.opencode.model;

/**
 * One tool invocation executed locally while answering a turn.
 */
public class ToolExecution {

    private String name;

    /** Raw JSON arguments string as the model wrote them. */
    private String arguments;

    /** Result handed back to the model (already truncated to the configured limit). */
    private String result;

    private boolean success;

    private long durationMs;

    public ToolExecution() {
    }

    public ToolExecution(String name, String arguments, String result, boolean success, long durationMs) {
        this.name = name;
        this.arguments = arguments;
        this.result = result;
        this.success = success;
        this.durationMs = durationMs;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
