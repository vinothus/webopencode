package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The incremental {@code delta} of a streaming choice.
 *
 * <p>Modelled exactly the way the Android reference reads it: {@code content} and
 * {@code reasoning_content} are plain strings, and {@code tool_calls} is a list of partial
 * {@link ToolCall}s whose {@code id}/{@code name} may be {@code null} in later fragments — those
 * nulls must never clobber what the first fragment already delivered.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Delta")
public class Delta {

    private String role;

    private String content;

    @JsonProperty("reasoning_content")
    private String reasoningContent;

    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
}
