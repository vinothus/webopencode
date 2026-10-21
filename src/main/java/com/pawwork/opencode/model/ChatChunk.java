package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One SSE {@code data:} payload from a streaming chat completion — an OpenAI chunk.
 *
 * <p>Every field may be absent in any given chunk: the gateway sends the role once, then content
 * fragments, then (optionally) tool-call fragments, and finally {@code finish_reason} + usage.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ChatCompletionChunk")
public class ChatChunk {

    private String id;

    private String object;

    private Long created;

    private String model;

    private List<ChunkChoice> choices;

    private Usage usage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChunkChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<ChunkChoice> choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /** @return the first choice, or {@code null} when the chunk carries only usage data. */
    public ChunkChoice firstChoice() {
        return choices == null || choices.isEmpty() ? null : choices.get(0);
    }
}
