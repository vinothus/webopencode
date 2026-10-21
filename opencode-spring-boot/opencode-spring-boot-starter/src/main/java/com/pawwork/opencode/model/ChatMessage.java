package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One OpenAI-wire chat message.
 *
 * <p>{@code content} is modelled as {@link JsonNode} because the wire format allows both a plain
 * string and an array of multimodal parts ({@code [{"type":"text",...},{"type":"image_url",...}]}).
 *
 * <p>Ported from the Android reference client
 * ({@code com.pawwork.android.chat.ChatFragment#wireHistory}), which keeps the full wire history —
 * including {@code assistant} messages carrying {@code tool_calls} and {@code tool} results — so the
 * tool loop survives across turns.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ChatCompletionMessage")
public class ChatMessage {

    /** {@code system} | {@code user} | {@code assistant} | {@code tool} */
    private String role;

    /** String or array of content parts; {@code null} is omitted from the payload. */
    private JsonNode content;

    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** Optional author name (only meaningful on {@code assistant} messages). */
    private String name;

    /** Reasoning models echo their chain of thought here instead of {@code content}. */
    @JsonProperty("reasoning_content")
    private String reasoningContent;

    public ChatMessage() {
    }

    public ChatMessage(String role, JsonNode content) {
        this.role = role;
        this.content = content;
    }

    // ------------------------------------------------------------ factories

    public static ChatMessage system(String text) {
        return new ChatMessage("system", text(text));
    }

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text(text));
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage("assistant", text(text));
    }

    public static ChatMessage tool(String toolCallId, String result) {
        ChatMessage m = new ChatMessage("tool", text(result));
        m.setToolCallId(toolCallId);
        return m;
    }

    /** Builds a string content node, the way {@code new JSONObject().put("content", s)} did on Android. */
    public static JsonNode text(String value) {
        return JsonNodeFactoryHolder.FACTORY.textNode(value == null ? "" : value);
    }

    /** {@code true} when the message carries no usable text. */
    public boolean hasNoText() {
        return contentAsText().isBlank();
    }

    /**
     * Flattens the content to plain text: strings pass through, multimodal arrays have their
     * {@code text} parts joined, everything else falls back to the raw JSON.
     */
    public String contentAsText() {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode t = part.get("text");
                if (t != null && t.isTextual()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t.asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    // ------------------------------------------------------------ accessors

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public JsonNode getContent() {
        return content;
    }

    public void setContent(JsonNode content) {
        this.content = content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    @Override
    public String toString() {
        return "ChatMessage{role=" + role + ", content=" + contentAsText()
                + ", toolCalls=" + (toolCalls == null ? 0 : toolCalls.size()) + "}";
    }
}
