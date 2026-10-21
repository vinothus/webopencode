package com.pawwork.opencode.demo.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pawwork.opencode.tools.ToolHandler;
import com.pawwork.opencode.tools.ToolSchemas;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Example {@link ToolHandler}s: three small, safe tools the model can call.
 *
 * <p>Register your own the same way — any {@code ToolHandler} bean is advertised to the gateway
 * (after the official OpenCode CLI schemas, whose names the free-tier gate requires) and executed
 * by the tool loop.</p>
 */
@Configuration
public class SampleTools {

    private static final int FETCH_LIMIT = 20_000;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Server time in ISO-8601, with the zone — useful for "what time is it there?" questions. */
    @Bean
    public ToolHandler currentTimeTool() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "current_time";
            }

            @Override
            public String description() {
                return "Current server date/time in ISO-8601, including the UTC offset.";
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                ObjectNode out = mapper.createObjectNode();
                out.put("iso", Instant.now().toString());
                out.put("local", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                        Instant.now().atZone(ZoneId.systemDefault())));
                out.put("zone", ZoneId.systemDefault().getId());
                out.put("epochSeconds", Instant.now().getEpochSecond());
                return out.toString();
            }
        };
    }

    /** Safe arithmetic — no script engine, just a tiny recursive-descent parser. */
    @Bean
    public ToolHandler calculateTool() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "calculate";
            }

            @Override
            public String description() {
                return "Evaluate an arithmetic expression: + - * / % ^, parentheses and decimals. "
                        + "No variables, no functions.";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parameters() {
                var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
                var properties = factory.objectNode();
                var expression = factory.objectNode();
                expression.put("type", "string");
                expression.put("description", "Arithmetic expression, e.g. (2+3)*4^2");
                properties.set("expression", expression);
                var required = factory.arrayNode();
                required.add("expression");
                return ToolSchemas.objectSchema(properties, required);
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                String expression = arguments.path("expression").asText("");
                ObjectNode out = mapper.createObjectNode();
                out.put("expression", expression);
                out.put("result", Arithmetic.evaluate(expression));
                return out.toString();
            }
        };
    }

    /** GETs a URL and returns a truncated text excerpt of the response. */
    @Bean
    public ToolHandler httpFetchTool() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        return new ToolHandler() {
            @Override
            public String name() {
                return "http_fetch";
            }

            @Override
            public String description() {
                return "Fetch a URL with HTTP GET and return status, content type and up to "
                        + FETCH_LIMIT + " characters of the body as text.";
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) throws Exception {
                String url = arguments.path("url").asText("");
                if (url.isBlank()) {
                    throw new IllegalArgumentException("'url' is required");
                }
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    throw new IllegalArgumentException("only http/https URLs are supported");
                }

                HttpRequest request = HttpRequest.newBuilder(uri)
                        .header("Accept", "text/*, application/json, application/xml;q=0.9, */*;q=0.8")
                        .header("User-Agent", "pawwork-opencode-spring/1.0")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body();
                if (body.length() > FETCH_LIMIT) {
                    body = body.substring(0, FETCH_LIMIT) + "\n…[truncated at " + FETCH_LIMIT + " chars]";
                }

                ObjectNode out = mapper.createObjectNode();
                out.put("url", url);
                out.put("status", response.statusCode());
                out.put("contentType", response.headers().firstValue("content-type").orElse(""));
                out.put("body", body);
                out.put("truncated", response.body() != null && response.body().length() > FETCH_LIMIT);
                return out.toString();
            }
        };
    }
}
