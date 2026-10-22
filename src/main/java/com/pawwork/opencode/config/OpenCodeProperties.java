package com.pawwork.opencode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Configuration for the OpenCode library, bound from {@code opencode.*}.
 *
 * <p>The top-level keys describe the primary provider (the OpenCode Zen gateway);
 * {@code opencode.providers.*} adds or overrides named providers so one application
 * can fan out to several gateways.</p>
 *
 * <pre>{@code
 * opencode:
 *   base-url: https://opencode.ai/zen/v1
 *   default-model: big-pickle
 *   providers:
 *     deepseek:
 *       base-url: https://api.deepseek.com/v1
 *       api-key: ${DEEPSEEK_API_KEY:}
 *       default-model: deepseek-chat
 * }</pre>
 */
@ConfigurationProperties(prefix = "opencode")
public class OpenCodeProperties {

    /** Master switch: {@code false} leaves the application context without the client beans. */
    private boolean enabled = true;

    /** Primary provider base URL (the OpenCode Zen gateway by default). */
    private String baseUrl = "https://opencode.ai/zen/v1";

    /** Bearer key for the primary provider. The Zen free tier needs none. */
    private String apiKey = "";

    /** Provider used when a request does not name one. */
    private String defaultProvider = "zen";

    /** Model used when a request does not name one. */
    private String defaultModel = "big-pickle";

    /**
     * Stable seed for the {@code x-opencode-session} header. The gateway only accepts ids shaped
     * like the ones the CLI mints, so the seed is hashed into that shape; supply a fixed value to
     * keep the same logical session across restarts, leave it blank for a per-instance one.
     * When {@code opencode.identity.rotate-hourly=true}, this value is used as a prefix and
     * the current UTC hour is appended (e.g., "pawwork-opencode-spring-2026100322").
     */
    private String stableSessionId = "";

    /** {@code User-Agent} sent to the gateway — the free tier checks it. */
    private String userAgent = "opencode/1.18.31";

    /** Value of {@code x-opencode-client}. */
    private String clientName = "cli";

    /** Value of {@code x-opencode-project}. */
    private String projectName = "global";

    /**
     * Prepend the official OpenCode CLI tool schemas (the 11 tools in {@code opencode_tools.json}).
     * Since 2026-09-18 the Zen free tier answers {@code 403 FreeTierError} to any request that does
     * not advertise them, so keep this on for the gateway.
     */
    private boolean includeOfficialTools = true;

    /** The Zen free tier serves only streaming calls; non-streaming gets {@code 403}. */
    private boolean forceStreaming = true;

    /** Output cap used for the gateway (the CLI asks for 32000). */
    private Integer maxTokens = 32000;

    /** TCP connect timeout in seconds. */
    private int connectTimeoutSeconds = 30;

    /** Time to first response byte in seconds (irrelevant for how long a stream may run). */
    private int requestTimeoutSeconds = 300;

    /**
     * Idle guard for a running SSE stream, in seconds. There is no per-read timeout on the JDK
     * HTTP client, so a scheduled watchdog closes a stream that has been silent this long.
     */
    private int streamIdleTimeoutSeconds = 300;

    /** Maximum model→tool→model rounds per turn (the Android client uses 12). */
    private int maxToolRounds = 12;

    /** Tool results longer than this are truncated before being sent back to the model. */
    private int toolResultLimit = 4000;

    /** Stop the loop after this many consecutive failures of the identical tool call. */
    private int maxRepeatedToolFailures = 5;

    /** Local tool execution settings (workspace root, shell consent, approval timeout). */
    private Tools tools = new Tools();

    /** The agent's system prompt — the instructions every conversation starts with. */
    private SystemPrompt systemPrompt = new SystemPrompt();

    private Retry retry = new Retry();

    /** Identity behavior configuration. */
    private Identity identity = new Identity();

    /** Named provider definitions, merged over the top-level defaults. */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    // ------------------------------------------------------------------ providers

    /**
     * Resolves a provider by name. An unknown name falls back to the default provider so a typo in
     * a request does not take the whole endpoint down.
     */
    public ProviderProfile resolve(String providerName) {
        String name = (providerName == null || providerName.isBlank()) ? defaultProvider : providerName.trim();
        Map<String, ProviderProfile> all = profiles();
        ProviderProfile profile = all.get(name);
        if (profile != null) {
            return profile;
        }
        ProviderProfile fallback = all.get(defaultProvider);
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("No OpenCode provider named '" + name + "' (known: "
                + new TreeSet<>(all.keySet()) + ")");
    }

    /** All configured providers, keyed by name; always contains {@code zen}. */
    public Map<String, ProviderProfile> profiles() {
        Map<String, ProviderProfile> out = new LinkedHashMap<>();
        out.put("zen", zenProfile());
        providers.forEach((name, p) -> out.put(name, merge(name, p)));
        return out;
    }

    private ProviderProfile zenProfile() {
        return build("zen", baseUrl, apiKey, defaultModel, true, true, maxTokens, userAgent, forceStreaming);
    }

    private ProviderProfile merge(String name, Provider p) {
        if ("zen".equals(name)) {
            return build(
                    name,
                    p.getBaseUrl() != null && !p.getBaseUrl().isBlank() ? p.getBaseUrl() : baseUrl,
                    p.getApiKey() != null ? p.getApiKey() : apiKey,
                    p.getDefaultModel() != null && !p.getDefaultModel().isBlank() ? p.getDefaultModel() : defaultModel,
                    p.getOpenCodeGateway() == null || p.getOpenCodeGateway(),
                    p.getTools() == null || p.getTools(),
                    p.getMaxTokens() != null ? p.getMaxTokens() : maxTokens,
                    p.getUserAgent() != null && !p.getUserAgent().isBlank() ? p.getUserAgent() : userAgent,
                    p.getForceStream() == null ? forceStreaming : p.getForceStream());
        }
        return build(
                name,
                p.getBaseUrl(),
                p.getApiKey() == null ? "" : p.getApiKey(),
                p.getDefaultModel(),
                Boolean.TRUE.equals(p.getOpenCodeGateway()),
                p.getTools() == null || p.getTools(),
                p.getMaxTokens() != null ? p.getMaxTokens() : 4096,
                p.getUserAgent() != null && !p.getUserAgent().isBlank() ? p.getUserAgent() : userAgent,
                Boolean.TRUE.equals(p.getForceStream()));
    }

    /**
     * Assembles a profile and applies two invariants the gateway cares about: anything hosted on
     * {@code opencode.ai} speaks the CLI dialect (the free-tier gate rejects calls without the
     * identity headers), and a model named {@code big-pickle} must not be sent a
     * {@code max_tokens} cap (its reasoning budget would otherwise swallow the visible answer).
     */
    private ProviderProfile build(String name, String baseUrl, String apiKey, String defaultModel,
                                  boolean gateway, boolean tools, Integer maxTokens,
                                  String userAgent, boolean forceStream) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        boolean gatewayDialect = gateway || base.contains("opencode.ai");
        Integer cap = maxTokens;
        if (defaultModel != null && defaultModel.startsWith("big-pickle") && !gatewayDialect) {
            cap = null;
        }
        return new ProviderProfile(
                name,
                base,
                apiKey == null ? "" : apiKey.trim(),
                defaultModel,
                gatewayDialect,
                tools,
                cap,
                userAgent,
                forceStream && gatewayDialect);
    }

    // ------------------------------------------------------------------ getters/setters

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String getStableSessionId() { return stableSessionId; }
    public void setStableSessionId(String stableSessionId) { this.stableSessionId = stableSessionId; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public boolean isIncludeOfficialTools() { return includeOfficialTools; }
    public void setIncludeOfficialTools(boolean includeOfficialTools) { this.includeOfficialTools = includeOfficialTools; }

    public boolean isForceStreaming() { return forceStreaming; }
    public void setForceStreaming(boolean forceStreaming) { this.forceStreaming = forceStreaming; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }

    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }

    public int getStreamIdleTimeoutSeconds() { return streamIdleTimeoutSeconds; }
    public void setStreamIdleTimeoutSeconds(int streamIdleTimeoutSeconds) { this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds; }

    public int getMaxToolRounds() { return maxToolRounds; }
    public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }

    public int getToolResultLimit() { return toolResultLimit; }
    public void setToolResultLimit(int toolResultLimit) { this.toolResultLimit = toolResultLimit; }

    public int getMaxRepeatedToolFailures() { return maxRepeatedToolFailures; }
    public void setMaxRepeatedToolFailures(int maxRepeatedToolFailures) { this.maxRepeatedToolFailures = maxRepeatedToolFailures; }

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public Tools getTools() { return tools; }
    public void setTools(Tools tools) { this.tools = tools; }

    public SystemPrompt getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(SystemPrompt systemPrompt) { this.systemPrompt = systemPrompt; }

    /** Seconds to wait for the browser to answer a {@code tool_approval} event. */
    public int getToolApprovalTimeoutSeconds() { return tools.getApprovalTimeoutSeconds(); }

    public Identity getIdentity() { return identity; }
    public void setIdentity(Identity identity) { this.identity = identity; }

    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }

    // ------------------------------------------------------------------ nested types

    /** Retry policy for transient failures, mirroring the Android client's 3-attempt backoff. */
    public static class Retry {

        private int maxAttempts = 3;
        private long backoffMillis = 1000;
        private long dnsBackoffMillis = 1500;
        private boolean retryOnTransientHttp = true;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getBackoffMillis() { return backoffMillis; }
        public void setBackoffMillis(long backoffMillis) { this.backoffMillis = backoffMillis; }

        public long getDnsBackoffMillis() { return dnsBackoffMillis; }
        public void setDnsBackoffMillis(long dnsBackoffMillis) { this.dnsBackoffMillis = dnsBackoffMillis; }

        public boolean isRetryOnTransientHttp() { return retryOnTransientHttp; }
        public void setRetryOnTransientHttp(boolean retryOnTransientHttp) { this.retryOnTransientHttp = retryOnTransientHttp; }
    }

    /** Identity behavior configuration. */
    public static class Identity {

        /**
         * When {@code true}, the stable session ID rotates every hour (UTC).
         * The configured {@code stable-session-id} is used as a prefix, and the current
         * UTC hour is appended (e.g., "pawwork-opencode-spring-2026100322").
         * This changes the {@code x-opencode-session} header every hour, which resets
         * the gateway's per-session rate limit accounting.
         */
        private boolean rotateHourly = false;

        public boolean isRotateHourly() { return rotateHourly; }
        public void setRotateHourly(boolean rotateHourly) { this.rotateHourly = rotateHourly; }
    }

    /** A named provider: every field is optional and falls back to the top-level defaults. */
    public static class Provider {

        private String baseUrl;
        private String apiKey;
        private String defaultModel;
        private Boolean openCodeGateway;
        private Boolean tools;
        private Integer maxTokens;
        private String userAgent;
        private Boolean forceStream;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

        public Boolean getOpenCodeGateway() { return openCodeGateway; }
        public void setOpenCodeGateway(Boolean openCodeGateway) { this.openCodeGateway = openCodeGateway; }

        public Boolean getTools() { return tools; }
        public void setTools(Boolean tools) { this.tools = tools; }

        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

        public Boolean getForceStream() { return forceStream; }
        public void setForceStream(Boolean forceStream) { this.forceStream = forceStream; }
    }

    /**
     * The agent's system prompt: the {@code opencode.system-prompt.*} block.
     *
     * <p>The OpenCode CLI never sends a bare user message — it opens every session with an agent
     * preamble, the current date, an {@code <env>} block describing the machine and the
     * repository's own {@code AGENTS.md}. This block controls whether that prompt is sent.</p>
     */
    public static class SystemPrompt {

        /** Master switch: {@code false} sends the user message on its own, as before. */
        private boolean enabled = true;

        /** Include the {@code <env>} block (working directory, workspace root, platform, temp dir). */
        private boolean includeEnvironment = true;

        /** Read {@code AGENTS.md} from the workspace root and append it to the prompt. */
        private boolean readAgentsFile = true;

        /** A file holding the prompt to use instead of the built-in one. Blank ⇒ the built-in one. */
        private String file = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isIncludeEnvironment() { return includeEnvironment; }
        public void setIncludeEnvironment(boolean includeEnvironment) { this.includeEnvironment = includeEnvironment; }

        public boolean isReadAgentsFile() { return readAgentsFile; }
        public void setReadAgentsFile(boolean readAgentsFile) { this.readAgentsFile = readAgentsFile; }

        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
    }

    /**
     * Local tool execution: the {@code opencode.tools.*} block.
     *
     * <p>{@code workspace} scopes the file tools. Leave it blank to use the process's working
     * directory, which keeps the application portable — the agent's reach follows wherever the jar
     * is started from.</p>
     */
    public static class Tools {

        /** Root the file tools may touch. Blank ⇒ the process working directory. */
        private String workspace = "";

        /**
         * Whether the shell tool runs without prompting in {@code auto} mode. Off by default: even
         * in auto mode {@code bash} is confirmed, because a command can do anything the app's user can.
         */
        private boolean allowShellWithoutApproval = false;

        /** Enable the built-in coding tools ({@code read}, {@code bash}, …). */
        private boolean enabled = true;

        /** Directory holding skill sub-directories, each with a {@code SKILL.md}. Blank ⇒ no skills. */
        private String skillsDir = "";

        /** Maximum sub-agent nesting depth for the {@code task} tool. */
        private int maxTaskDepth = 2;

        /** Seconds the stream waits for a tool-approval answer before denying the call. */
        private int approvalTimeoutSeconds = 300;

        /** The shell the {@code bash} tool runs commands with. */
        private Shell shell = new Shell();

        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }

        public boolean isAllowShellWithoutApproval() { return allowShellWithoutApproval; }
        public void setAllowShellWithoutApproval(boolean value) { this.allowShellWithoutApproval = value; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getSkillsDir() { return skillsDir; }
        public void setSkillsDir(String skillsDir) { this.skillsDir = skillsDir; }

        public int getMaxTaskDepth() { return maxTaskDepth; }
        public void setMaxTaskDepth(int maxTaskDepth) { this.maxTaskDepth = maxTaskDepth; }

        public int getApprovalTimeoutSeconds() { return approvalTimeoutSeconds; }
        public void setApprovalTimeoutSeconds(int value) { this.approvalTimeoutSeconds = value; }

        public Shell getShell() { return shell; }
        public void setShell(Shell shell) { this.shell = shell; }
    }

    /**
     * The shell the {@code bash} tool uses: the {@code opencode.tools.shell.*} block.
     *
     * <p>Left blank, the tool finds a shell by itself — a POSIX shell first (Git Bash or WSL on
     * Windows), then {@code cmd.exe}, then PowerShell. Point it at an executable to override that.</p>
     */
    public static class Shell {

        /** The shell executable to run. Blank ⇒ detect one. */
        private String executable = "";

        public String getExecutable() { return executable; }
        public void setExecutable(String executable) { this.executable = executable; }
    }
}
