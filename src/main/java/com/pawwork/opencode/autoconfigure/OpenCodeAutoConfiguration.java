package com.pawwork.opencode.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.client.HourlyRotatingOpenCodeIdentity;
import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.client.OpenCodeIdentity;
import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.prompt.AgentProfile;
import com.pawwork.opencode.prompt.AgentPrompt;
import com.pawwork.opencode.prompt.ServerEnvironment;
import com.pawwork.opencode.prompt.ToolNotes;
import com.pawwork.opencode.service.ChatService;
import com.pawwork.opencode.tools.OpenCodeToolSchemas;
import com.pawwork.opencode.tools.ToolApproval;
import com.pawwork.opencode.tools.ToolHandler;
import com.pawwork.opencode.tools.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Auto-configuration for the OpenCode library.
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenCodeProperties.class)
@ConditionalOnProperty(prefix = "opencode", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenCodeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "opencode.identity", name = "rotate-hourly", havingValue = "true", matchIfMissing = false)
    public OpenCodeIdentity opencodeIdentityHourly(OpenCodeProperties properties) {
        return new HourlyRotatingOpenCodeIdentity(properties.getStableSessionId());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "opencode.identity", name = "rotate-hourly", havingValue = "false", matchIfMissing = true)
    public OpenCodeIdentity opencodeIdentityStatic(OpenCodeProperties properties) {
        return new OpenCodeIdentity(properties.getStableSessionId());
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenCodeToolSchemas opencodeToolSchemas(ObjectProvider<ObjectMapper> mapperProvider) {
        return OpenCodeToolSchemas.load(mapper(mapperProvider));
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAiClient opencodeClient(OpenCodeProperties properties,
                                       OpenCodeIdentity identity,
                                       OpenCodeToolSchemas schemas,
                                       ObjectProvider<ObjectMapper> mapperProvider) {
        return new OpenAiClient(properties, identity, mapper(mapperProvider), schemas.tools());
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry opencodeToolRegistry(OpenCodeProperties properties,
                                             ObjectProvider<ToolHandler> handlers,
                                             ObjectProvider<ObjectMapper> mapperProvider) {
        List<ToolHandler> registered = handlers.orderedStream().toList();
        return new ToolRegistry(mapper(mapperProvider), registered, properties.getToolResultLimit());
    }

    /** The facts about the machine the agent runs on — the CLI's {@code <env>} block. */
    @Bean
    @ConditionalOnMissingBean
    public ServerEnvironment opencodeServerEnvironment(OpenCodeProperties properties) {
        return new ServerEnvironment(properties);
    }

    /** The agent's system prompt: preamble, date, environment and the repository's own instructions. */
    @Bean
    @ConditionalOnMissingBean
    public AgentPrompt opencodeAgentPrompt(OpenCodeProperties properties) {
        return new AgentPrompt(properties);
    }

    /** The per-tool notes appended to the CLI's tool descriptions. */
    @Bean
    @ConditionalOnMissingBean
    public ToolNotes opencodeToolNotes(ServerEnvironment environment) {
        return new ToolNotes(environment);
    }

    /** The system prompt and the tool notes, bundled for the chat service. */
    @Bean
    @ConditionalOnMissingBean
    public AgentProfile opencodeAgentProfile(ServerEnvironment environment,
                                             AgentPrompt prompt,
                                             ToolNotes notes) {
        return new AgentProfile(environment, prompt, notes);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatService opencodeChatService(OpenAiClient client,
                                           OpenCodeProperties properties,
                                           ToolRegistry registry,
                                           OpenCodeToolSchemas schemas,
                                           ObjectProvider<ToolApproval> approvalProvider,
                                           ObjectProvider<AgentProfile> profileProvider,
                                           ObjectProvider<ObjectMapper> mapperProvider) {
        ToolApproval approval = approvalProvider.getIfAvailable(ToolApproval::alwaysAllow);
        return new ChatService(client, properties, registry, mapper(mapperProvider), schemas.tools(),
                approval, 0, profileProvider.getIfAvailable());
    }

    /**
     * Default consent policy: every call is allowed, except tools that declare themselves as
     * always-requiring-confirmation (the shell, which the host may still permit explicitly).
     * The demo app overrides this bean to honour the {@code auto}/{@code ask}/{@code off} choice
     * made in the browser.
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolApproval opencodeToolApproval(OpenCodeProperties properties) {
        boolean allowShell = properties.getTools().isAllowShellWithoutApproval();
        return new ToolApproval() {
            @Override
            public String mode() {
                return "auto";
            }

            @Override
            public Decision decide(String toolName, String arguments, boolean force) {
                return Decision.ALLOW;
            }

            @Override
            public boolean requiresConfirmation(String toolName, com.fasterxml.jackson.databind.JsonNode arguments) {
                return "bash".equals(toolName) && !allowShell;
            }
        };
    }

    private static ObjectMapper mapper(ObjectProvider<ObjectMapper> provider) {
        return provider.getIfAvailable(OpenAiClient::defaultMapper);
    }
}
