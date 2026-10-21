package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code webfetch} tool: GETs a URL and returns it as text, markdown or HTML.
 *
 * <p>Markdown is the default, as in the OpenCode CLI. The HTML→markdown step is a deliberately small
 * converter — it strips scripts/styles, maps the common block and inline tags, and decodes the usual
 * entities. It is not a full HTML parser; it produces readable prose, which is what the model needs.</p>
 */
public final class WebFetchTool {

    static final int LIMIT = 50_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public WebFetchTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ToolHandler handler() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "webfetch";
            }

            @Override
            public String description() {
                return "Fetch a URL and return its content. Markdown by default, or text/html. "
                        + "Use for reading documentation and web pages; not for search.";
            }

            @Override
            public JsonNode parameters() {
                ObjectNode properties = mapper.createObjectNode();
                properties.set("url", typed("string", "The URL to fetch content from"));
                ObjectNode format = typed("string", "How to return the content: text, markdown or html.");
                format.put("default", "markdown");
                properties.set("format", format);
                ObjectNode timeout = mapper.createObjectNode();
                timeout.put("type", "number");
                timeout.put("description", "Optional timeout in seconds (max 120).");
                properties.set("timeout", timeout);

                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", properties);
                schema.putArray("required").add("url");
                return schema;
            }

            @Override
            public String execute(JsonNode arguments) throws Exception {
                String raw = arguments.path("url").asText("").trim();
                if (raw.isEmpty()) {
                    return error("'url' is required");
                }
                String format = arguments.path("format").asText("markdown").trim().toLowerCase();
                if (!format.equals("text") && !format.equals("markdown") && !format.equals("html")) {
                    return error("unsupported format '" + format + "' — use text, markdown or html");
                }

                URI uri;
                try {
                    uri = URI.create(raw);
                } catch (IllegalArgumentException e) {
                    return error("invalid URL: " + raw);
                }
                String scheme = uri.getScheme();
                if (scheme == null) {
                    uri = URI.create("https://" + raw);   // CLI behaviour: upgrade a bare host
                    scheme = uri.getScheme();
                } else if ("http".equalsIgnoreCase(scheme)) {
                    uri = URI.create("https://" + raw.substring("http://".length()));
                    scheme = "https";
                }
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                    return error("only http/https URLs are supported");
                }

                Duration timeout = MAX_REQUEST_TIMEOUT;
                if (arguments.has("timeout")) {
                    double seconds = arguments.path("timeout").asDouble(0);
                    if (seconds > 0) {
                        timeout = Duration.ofMillis(Math.min((long) (seconds * 1000), MAX_REQUEST_TIMEOUT.toMillis()));
                    }
                }

                HttpRequest request = HttpRequest.newBuilder(uri)
                        .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.8")
                        .header("User-Agent", "Mozilla/5.0 (compatible; pawwork-opencode-spring/1.0)")
                        .timeout(timeout)
                        .GET()
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                String contentType = response.headers().firstValue("content-type").orElse("");
                String body = response.body() == null ? "" : response.body();

                String content = switch (format) {
                    case "html" -> body;
                    case "text" -> looksLikeHtml(contentType, body) ? HtmlToText.toText(body) : body;
                    default -> looksLikeHtml(contentType, body) ? HtmlToText.toMarkdown(body) : body;
                };

                boolean truncated = content.length() > LIMIT;
                if (truncated) {
                    content = content.substring(0, LIMIT) + "\n…[truncated at " + LIMIT + " chars]";
                }

                ObjectNode result = mapper.createObjectNode();
                result.put("url", uri.toString());
                result.put("status", response.statusCode());
                result.put("contentType", contentType);
                result.put("format", format);
                result.put("truncated", truncated);
                result.put("content", content);
                return result.toString();
            }
        };
    }

    private static boolean looksLikeHtml(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("html")) {
            return true;
        }
        String head = body.length() > 400 ? body.substring(0, 400) : body;
        String lower = head.toLowerCase();
        return lower.contains("<!doctype html") || lower.contains("<html") || lower.contains("<body");
    }

    private ObjectNode typed(String type, String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }

    private String error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        return node.toString();
    }

    /**
     * A compact HTML→text/markdown converter. Not a spec-compliant parser: it removes the parts that
     * carry no meaning (scripts, styles, comments), turns block tags into line breaks, maps headings,
     * lists, links and code, and collapses whitespace.
     */
    static final class HtmlToText {

        private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
                "(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>");
        private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
        private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
        private static final Pattern MANY_BLANK = Pattern.compile("\\n{3,}");
        private static final Pattern SPACES = Pattern.compile("[ \\t\\u00a0]+");

        private HtmlToText() {
        }

        static String toText(String html) {
            return collapse(stripTags(html));
        }

        static String toMarkdown(String html) {
            String body = html;
            body = COMMENT.matcher(body).replaceAll("");
            body = SCRIPT_OR_STYLE.matcher(body).replaceAll(" ");

            // Headings before the generic tag sweep.
            for (int level = 1; level <= 6; level++) {
                Pattern heading = Pattern.compile("(?is)<h" + level + "[^>]*>(.*?)</h" + level + ">");
                body = heading.matcher(body).replaceAll(match -> "\n\n"
                        + "#".repeat(Math.max(1, Integer.parseInt(match.group(0).substring(2, 3))))
                        + " " + stripTags(match.group(1)).trim() + "\n\n");
            }
            // Links and code.
            body = Pattern.compile("(?is)<a[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>")
                    .matcher(body)
                    .replaceAll(m -> {
                        String text = stripTags(m.group(2)).trim();
                        String href = m.group(1);
                        return text.isEmpty() ? href : "[" + text + "](" + href + ")";
                    });
            body = Pattern.compile("(?is)<(strong|b)>(.*?)</\\1>")
                    .matcher(body).replaceAll(m -> "**" + stripTags(m.group(2)).trim() + "**");
            body = Pattern.compile("(?is)<(em|i)>(.*?)</\\1>")
                    .matcher(body).replaceAll(m -> "*" + stripTags(m.group(2)).trim() + "*");
            body = Pattern.compile("(?is)<(code|pre)[^>]*>(.*?)</\\1>")
                    .matcher(body).replaceAll(m -> "\n```\n" + stripTags(m.group(2)).trim() + "\n```\n");
            body = Pattern.compile("(?is)<li[^>]*>(.*?)</li>")
                    .matcher(body).replaceAll(m -> "\n- " + stripTags(m.group(1)).trim());

            // Block boundaries become newlines, then drop every remaining tag.
            body = body.replaceAll("(?i)</(p|div|section|article|header|footer|tr|ul|ol|table|blockquote)>", "\n\n");
            body = body.replaceAll("(?i)<br\\s*/?>", "\n");
            body = stripTags(body);
            return collapse(body);
        }

        /** Removes tags and decodes the common entities. */
        private static String stripTags(String html) {
            String text = TAG.matcher(html).replaceAll(" ");
            return decodeEntities(text);
        }

        private static String decodeEntities(String text) {
            String out = text
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&apos;", "'").replace("&mdash;", "—").replace("&ndash;", "–")
                    .replace("&hellip;", "…").replace("&copy;", "©").replace("&reg;", "®");
            // Numeric entities.
            Matcher numeric = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(out);
            StringBuilder sb = new StringBuilder();
            while (numeric.find()) {
                int code = Integer.parseInt(numeric.group(2), numeric.group(1).isEmpty() ? 10 : 16);
                numeric.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
            }
            numeric.appendTail(sb);
            return sb.toString();
        }

        private static String collapse(String text) {
            String out = SPACES.matcher(text).replaceAll(" ");
            out = out.replaceAll(" *\\n *", "\n");
            return MANY_BLANK.matcher(out).replaceAll("\n\n").trim();
        }
    }
}
