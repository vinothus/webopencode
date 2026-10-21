package com.pawwork.opencode.demo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document metadata shown in Swagger UI.
 *
 * <p>Schemas are named after the official OpenAI spec ({@code ChatCompletionRequest},
 * {@code ChatCompletionChunk}, {@code ErrorObject}, …) so code generators produce clients that
 * look like ordinary OpenAI clients.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI opencodeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpenCode AI Library API")
                        .version("1.0.0")
                        .description("""
                                Send and receive requests/responses to AI models through the OpenCode \
                                gateway (OpenAI-compatible wire), from Java.

                                Two surfaces:

                                * **/v1/** — the OpenAI-spec API: `POST /v1/chat/completions` \
                                (JSON and `stream:true` SSE), `GET /v1/models`, `GET /v1/models/{model}`. \
                                Point Cursor, LangChain, Claude Code (OpenAI mode) or any OpenAI SDK at \
                                this base URL and it works unchanged.
                                * **/api/** — the library's own extras: conversation sessions, the \
                                server-side model⇄tool loop, and event-stream streaming.

                                The gateway identity headers the OpenCode CLI sends \
                                (`x-opencode-session`, `x-opencode-client`, `x-opencode-project`, \
                                `x-opencode-request`, CLI `User-Agent`) are attached automatically, \
                                as are the official CLI tool schemas the free tier requires. Declared \
                                tool calls stay declared: tools the caller never asked for are never \
                                returned to it.

                                Errors use OpenAI's error object: \
                                `{"error":{"message":…,"type":…,"param":…,"code":…}}`.""")
                        .contact(new Contact().name("PawWork").url("https://opencode.ai"))
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("BearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("Accepted and ignored: the upstream credential comes "
                                        + "from `opencode.providers.*.api-key`.")))
                .servers(List.of(new Server().url("http://localhost:8080").description("This server")));
    }
}
