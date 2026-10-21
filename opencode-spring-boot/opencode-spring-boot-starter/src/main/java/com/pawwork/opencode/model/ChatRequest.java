package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outgoing {@code POST /chat/completions} body — the OpenAI chat-completions request shape.
 *
 * <p>Nulls are omitted so that provider-specific defaults (e.g. a gateway that only accepts
 * streaming) stay in charge of the fields they own.</p>
 *
 * <p>Every field of the OpenAI spec is either modelled explicitly below or captured by
 * {@link #putUnknown(String, JsonNode)} and forwarded verbatim: an SDK that sends
 * {@code stop}, {@code seed}, {@code response_format}, {@code logit_bias}, … must never have it
 * silently dropped on the way to the provider.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ChatCompletionRequest")
public class ChatRequest {

    private String model;

    private List<ChatMessage> messages = new ArrayList<>();

    private Boolean stream;

    @JsonProperty("stream_options")
    private JsonNode streamOptions;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** Newer OpenAI field; forwarded alongside (never instead of) the gateway's own budget. */
    @JsonProperty("max_completion_tokens")
    private Integer maxCompletionTokens;

    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    /** Sampling parameter the spec allows but clients rarely set — forwarded as-is. */
    private Integer n;

    /** Stop sequences: string or array of strings. */
    private JsonNode stop;

    private Long seed;

    @JsonProperty("response_format")
    private JsonNode responseFormat;

    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    @JsonProperty("logit_bias")
    private JsonNode logitBias;

    private Boolean logprobs;

    @JsonProperty("top_logprobs")
    private Integer topLogprobs;

    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;

    private JsonNode metadata;

    private JsonNode store;

    private List<ToolDefinition> tools;

    @JsonProperty("tool_choice")
    private JsonNode toolChoice;

    /** End-user identifier echoed back by providers that bill per user. */
    private String user;

    /** Anything else the SDK sent — kept so spec additions are never lost in transit. */
    private Map<String, JsonNode> unknown = new LinkedHashMap<>();

    public ChatRequest() {
    }

    public ChatRequest(String model, List<ChatMessage> messages) {
        this.model = model;
        this.messages = messages;
    }

    /** Jackson catch-all: preserves request fields this class does not model. */
    @JsonAnySetter
    public void putUnknown(String name, JsonNode value) {
        if (name != null && value != null) {
            unknown.put(name, value);
        }
    }

    /** Emitted back onto the wire so unknown fields ride along to the provider. */
    @JsonAnyGetter
    @Schema(hidden = true)
    public Map<String, JsonNode> getUnknown() {
        return unknown;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public ChatRequest addMessage(ChatMessage message) {
        this.messages.add(message);
        return this;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public JsonNode getStreamOptions() {
        return streamOptions;
    }

    public void setStreamOptions(JsonNode streamOptions) {
        this.streamOptions = streamOptions;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getMaxCompletionTokens() {
        return maxCompletionTokens;
    }

    public void setMaxCompletionTokens(Integer maxCompletionTokens) {
        this.maxCompletionTokens = maxCompletionTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getN() {
        return n;
    }

    public void setN(Integer n) {
        this.n = n;
    }

    public JsonNode getStop() {
        return stop;
    }

    public void setStop(JsonNode stop) {
        this.stop = stop;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public JsonNode getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(JsonNode responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public JsonNode getLogitBias() {
        return logitBias;
    }

    public void setLogitBias(JsonNode logitBias) {
        this.logitBias = logitBias;
    }

    public Boolean getLogprobs() {
        return logprobs;
    }

    public void setLogprobs(Boolean logprobs) {
        this.logprobs = logprobs;
    }

    public Integer getTopLogprobs() {
        return topLogprobs;
    }

    public void setTopLogprobs(Integer topLogprobs) {
        this.topLogprobs = topLogprobs;
    }

    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    public void setParallelToolCalls(Boolean parallelToolCalls) {
        this.parallelToolCalls = parallelToolCalls;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }

    public JsonNode getStore() {
        return store;
    }

    public void setStore(JsonNode store) {
        this.store = store;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public void setTools(List<ToolDefinition> tools) {
        this.tools = tools;
    }

    public JsonNode getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(JsonNode toolChoice) {
        this.toolChoice = toolChoice;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }
}
