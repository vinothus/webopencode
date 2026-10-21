package com.pawwork.opencode.demo.dto;

import com.pawwork.opencode.model.ChatMessage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Request body of the library-level chat endpoints.
 */
@Schema(name = "ChatApiRequest", description = "A single user turn for a conversation session.")
public class ChatApiRequest {

    @Schema(description = "Conversation key. Omit it on the first turn — a fresh id comes back "
            + "in the response and must be sent with every following turn.", example = "sess_9f2c4ab1de77aa10b3c4")
    private String sessionId;

    @Schema(description = "What the user is asking.", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "How many seconds are in a week? Use the calculate tool.")
    private String message;

    @Schema(description = "Logical provider name from opencode.providers.* — omit for the default.",
            example = "zen")
    private String provider;

    @Schema(description = "Model id — omit for the provider's default model.", example = "big-pickle")
    private String model;

    @Schema(description = "Optional system prompt applied to a brand-new session only.")
    private String systemPrompt;

    @Schema(description = "Tool consent mode: 'auto' runs everything (the shell still asks), "
            + "'ask' pauses for Allow/Deny before each call, 'off' refuses every tool.",
            example = "ask", allowableValues = {"auto", "ask", "off"})
    private String toolMode;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getToolMode() {
        return toolMode;
    }

    public void setToolMode(String toolMode) {
        this.toolMode = toolMode;
    }

    /** @return a defensive copy, so a malformed body fails fast with a clear message. */
    public void validate() {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("'message' is required and must not be blank");
        }
    }
}
