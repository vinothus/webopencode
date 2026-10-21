package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Incoming {@code 200 OK} body of a non-streaming (or stream-aggregated) chat completion.
 *
 * <p>Unknown provider fields (id formats, {@code system_fingerprint}, …) are ignored: Spring Boot's
 * ObjectMapper disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, and the library's own mapper does the
 * same.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ChatCompletion")
public class ChatResponse {

    private String id;

    private String object;

    private Long created;

    private String model;

    private List<Choice> choices;

    private Usage usage;

    /** Populated by the gateway when a request was rejected before it reached a model. */
    private JsonError error;

    @JsonProperty("error_msg")
    private String errorMsg;

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

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public JsonError getError() {
        return error;
    }

    public void setError(JsonError error) {
        this.error = error;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    /** @return the first choice's message, or an empty assistant message when the body has none. */
    public ChatMessage firstMessage() {
        if (choices == null || choices.isEmpty() || choices.get(0) == null) {
            return ChatMessage.assistant("");
        }
        ChatMessage m = choices.get(0).getMessage();
        return m == null ? ChatMessage.assistant("") : m;
    }

    /**
     * Best-effort error text from the many shapes gateways use: {@code error_msg}, OpenAI's
     * {@code error.message}, or the raw body.
     */
    public String errorMessage() {
        if (errorMsg != null && !errorMsg.isBlank()) {
            return errorMsg;
        }
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return null;
    }
}
