package com.pawwork.opencode.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One configured provider as advertised by {@code GET /api/providers}.
 */
@Schema(name = "ProviderInfo", description = "A configured AI provider the library can call.")
public class ProviderInfo {

    @Schema(example = "zen")
    private String name;

    @Schema(example = "https://opencode.ai/zen/v1")
    private String baseUrl;

    @Schema(example = "big-pickle")
    private String defaultModel;

    @Schema(description = "True when the OpenCode CLI dialect (identity headers, forced streaming, "
            + "official tool schemas) is used for this provider.")
    private boolean opencodeGateway;

    @Schema(description = "Whether tools may be advertised to this provider.")
    private boolean tools;

    @Schema(description = "True when an API key is configured (never the key itself).")
    private boolean secured;

    @Schema(description = "Models known to this application for this provider.", example = "[\"big-pickle\"]")
    private List<String> models;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public boolean isOpencodeGateway() {
        return opencodeGateway;
    }

    public void setOpencodeGateway(boolean opencodeGateway) {
        this.opencodeGateway = opencodeGateway;
    }

    public boolean isTools() {
        return tools;
    }

    public void setTools(boolean tools) {
        this.tools = tools;
    }

    public boolean isSecured() {
        return secured;
    }

    public void setSecured(boolean secured) {
        this.secured = secured;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }
}
