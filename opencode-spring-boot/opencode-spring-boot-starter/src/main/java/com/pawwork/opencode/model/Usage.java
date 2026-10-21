package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Token accounting. Only present when the request carried
 * {@code "stream_options":{"include_usage":true}} — which the OpenCode gateway path always sets.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "CompletionUsage")
public class Usage {

    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    @JsonProperty("total_tokens")
    private Integer totalTokens;

    public Usage() {
    }

    public Usage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    /** Adds {@code other} into this instance (SSE reports one usage block per response). */
    public void merge(Usage other) {
        if (other == null) {
            return;
        }
        promptTokens = sum(promptTokens, other.promptTokens);
        completionTokens = sum(completionTokens, other.completionTokens);
        totalTokens = sum(totalTokens, other.totalTokens);
    }

    private static Integer sum(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }
}
