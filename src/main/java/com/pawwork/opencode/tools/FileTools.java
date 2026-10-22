package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * The five file tools the model may call — {@code read}, {@code glob}, {@code grep},
 * {@code write} and {@code edit} — implemented in pure Java against a {@link Workspace}.
 *
 * <p>Every path is resolved through the workspace, so nothing outside it is reachable. Reads and
 * listings are bounded ({@link #MAX_READ_LINES}, {@link #MAX_MATCHES}) so one call cannot flood the
 * model's context; the registry truncates again at {@code opencode.tool-result-limit}.</p>
 */
public final class FileTools {

    static final int MAX_READ_LINES = 2000;
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_GLOB_RESULTS = 300;
    static final int MAX_MATCHES = 200;
    static final int MAX_LINE_PREVIEW = 300;
    /** Directories never walked: noise, and in a container often enormous. */
    private static final List<String> SKIP_DIRS = List.of(".git", "node_modules", "target", "dist", "build");

    private final Workspace workspace;
    private final ObjectMapper mapper;

    public FileTools(Workspace workspace, ObjectMapper mapper) {
        this.workspace = workspace;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ read

    public ToolHandler read() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description() {
                return "Read a text file inside the workspace. Returns numbered lines. "
                        + "Use offset and limit to page through a large file.";
            }

            @Override
            public JsonNode parameters() {
                return schemas(
                        props(
                                "filePath", "string", "Path of the file to read, absolute or relative to the workspace root.",
                                "offset", "integer", "1-based line to start at (default 1).",
                                "limit", "integer", "Maximum lines to return (default " + MAX_READ_LINES + ")."),
                        "filePath");
            }

            @Override
            public String execute(JsonNode arguments) throws Exception {
                Path file;
                try {
                    file = workspace.resolve(pathArgument(arguments));
                } catch (SecurityException e) {
                    return error(e.getMessage());
                }
                if (!Files.exists(file)) {
                    return error("file not found: " + workspace.display(file));
                }
                if (Files.isDirectory(file)) {
                    return listing(file);
                }
                long size = Files.size(file);
                if (size > MAX_FILE_BYTES) {
                    return error("file is " + size + " bytes, larger than the " + MAX_FILE_BYTES
                            + "-byte read limit: " + workspace.display(file));
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

                int offset = Math.max(1, arguments.path("offset").asInt(1));
                int limit = arguments.path("limit").asInt(MAX_READ_LINES);
                if (limit <= 0) {
                    limit = MAX_READ_LINES;
                }
                int from = Math.min(offset - 1, lines.size());
                int to = Math.min(from + limit, lines.size());

                StringBuilder out = new StringBuilder();
                for (int i = from; i < to; i++) {
                    out.append(i + 1).append(": ").append(lines.get(i)).append('\n');
                }
                ObjectNode result = mapper.createObjectNode();
                result.put("path", workspace.display(file));
                result.put("lines", lines.size());
                result.put("from", from + 1);
                result.put("to", to);
                result.put("truncated", to < lines.size());
                result.put("content", out.toString());
                return result.toString();
            }
        };
    }

    private String listing(Path dir) throws IOException {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(500)
                    .forEach(p -> entries.add((Files.isDirectory(p) ? "[dir]  " : "       ")
                            + workspace.display(p)));
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("path", workspace.display(dir));
        result.put("directory", true);
        ArrayNode array = result.putArray("entries");
        entries.forEach(array::add);
        return result.toString();
    }

    // ------------------------------------------------------------------ glob

    public ToolHandler glob() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "glob";
            }

            @Override
            public String description() {
                return "Find files by glob pattern, e.g. '**/*.java'. Returns matching paths.";
            }

            @Override
            public JsonNode parameters() {
                return schemas(
                        props(
                                "pattern", "string", "Glob pattern such as '**/*.ts'.",
                                "path", "string", "Directory to search under (default: workspace root)."),
                        "pattern");
            }

            @Override
            public String execute(JsonNode arguments) throws Exception {
                String pattern = arguments.path("pattern").asText("").trim();
                if (pattern.isEmpty()) {
                    return error("'pattern' is required");
                }
                Path base;
                try {
                    base = workspace.resolve(arguments.path("path").asText(""));
                } catch (SecurityException e) {
                    return error(e.getMessage());
                }
                if (!Files.isDirectory(base)) {
                    return error("not a directory: " + workspace.display(base));
                }
                PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + pattern);

                List<String> matches = new ArrayList<>();
                walk(base, path -> {
                    if (matches.size() >= MAX_GLOB_RESULTS) {
                        return;
                    }
                    Path relative = base.relativize(path);
                    if (matcher.matches(relative) || matcher.matches(path.getFileName())) {
                        matches.add(workspace.display(path));
                    }
                });

                ObjectNode result = mapper.createObjectNode();
                result.put("pattern", pattern);
                result.put("root", workspace.display(base));
                result.put("count", matches.size());
                result.put("truncated", matches.size() >= MAX_GLOB_RESULTS);
                ArrayNode array = result.putArray("files");
                matches.forEach(array::add);
                return result.toString();
            }
        };
    }

    // ------------------------------------------------------------------ grep

    public ToolHandler grep() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "grep";
            }

            @Override
            public String description() {
                return "Search file contents with a regular expression. Returns paths, line numbers "
                        + "and matching line previews.";
            }

            @Override
            public JsonNode parameters() {
                return schemas(
                        props(
                                "pattern", "string", "Regular expression to search for.",
                                "path", "string", "File or directory to search (default: workspace root).",
                                "include", "string", "Optional file glob filter, e.g. '*.java'.",
                                "caseSensitive", "boolean", "Case-sensitive match (default true)."),
                        "pattern");
            }

            @Override
            public String execute(JsonNode arguments) throws Exception {
                String raw = arguments.path("pattern").asText("").trim();
                if (raw.isEmpty()) {
                    return error("'pattern' is required");
                }
                boolean caseSensitive = !arguments.has("caseSensitive")
                        || arguments.path("caseSensitive").asBoolean(true);
                Pattern pattern;
                try {
                    pattern = Pattern.compile(raw, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    return error("invalid regular expression: " + e.getDescription());
                }
                String include = arguments.path("include").asText("").trim();
                PathMatcher includeMatcher = include.isEmpty() ? null
                        : argumentsObjectFileSystem().getPathMatcher("glob:" + include);

                Path base;
                try {
                    base = workspace.resolve(arguments.path("path").asText(""));
                } catch (SecurityException e) {
                    return error(e.getMessage());
                }

                List<ObjectNode> matches = new ArrayList<>();
                if (Files.isRegularFile(base)) {
                    scanFile(base, pattern, matches);
                } else if (Files.isDirectory(base)) {
                    walk(base, path -> {
                        if (matches.size() >= MAX_MATCHES) {
                            return;
                        }
                        if (includeMatcher != null && !includeMatcher.matches(path.getFileName())) {
                            return;
                        }
                        scanFile(path, pattern, matches);
                    });
                } else {
                    return error("path not found: " + workspace.display(base));
                }

                ObjectNode result = mapper.createObjectNode();
                result.put("pattern", raw);
                result.put("count", matches.size());
                result.put("truncated", matches.size() >= MAX_MATCHES);
                ArrayNode array = result.putArray("matches");
                matches.forEach(array::add);
                return result.toString();
            }
        };
    }

    private java.nio.file.FileSystem argumentsObjectFileSystem() {
        return workspace.root().getFileSystem();
    }

    private void scanFile(Path file, Pattern pattern, List<ObjectNode> matches) {
        if (matches.size() >= MAX_MATCHES) {
            return;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
                Matcher matcher = pattern.matcher(lines.get(i));
                if (matcher.find()) {
                    ObjectNode hit = mapper.createObjectNode();
                    hit.put("path", workspace.display(file));
                    hit.put("line", i + 1);
                    String text = lines.get(i).trim();
                    hit.put("text", text.length() > MAX_LINE_PREVIEW
                            ? text.substring(0, MAX_LINE_PREVIEW) : text);
                    matches.add(hit);
                }
            }
        } catch (IOException ignored) {
            // unreadable/binary file — skip it rather than fail the whole search
        }
    }

    // ------------------------------------------------------------------ write

    public ToolHandler write() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "write";
            }

            @Override
            public String description() {
                return "Create a file or replace its entire contents, inside the workspace. "
                        + "Parent directories are created as needed.";
            }

            @Override
            public JsonNode parameters() {
                return schemas(
                        props(
                                "filePath", "string", "Path of the file to write, inside the workspace.",
                                "content", "string", "Full file contents to write."),
                        "filePath", "content");
            }

            @Override
            public String execute(JsonNode arguments) throws Exception {
                Path file;
                try {
                    file = workspace.resolve(pathArgument(arguments));
                } catch (SecurityException e) {
                    return error(e.getMessage());
                }
                String content = arguments.path("content").asText("");
                boolean existed = Files.exists(file);
                if (existed && Files.isDirectory(file)) {
                    return error("path is a directory: " + workspace.display(file));
                }
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);

                ObjectNode result = mapper.createObjectNode();
                result.put("path", workspace.display(file));
                result.put("created", !existed);
                result.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
                result.put("lines", content.isEmpty() ? 0 : content.split("\n", -1).length);
                return result.toString();
            }
        };
    }

    // ------------------------------------------------------------------ edit

    public ToolHandler edit() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "edit";
            }

            @Override
            public String description() {
                return "Replace an exact piece of text in a workspace file. By default oldString must "
                        + "occur exactly once; set replaceAll to change every occurrence.";
            }

            @Override
            public JsonNode parameters() {
                return schemas(
                        props(
                                "filePath", "string", "Path of the file to edit.",
                                "oldString", "string", "Exact text to find.",
                                "newString", "string", "Replacement text (must differ from oldString).",
                                "replaceAll", "boolean", "Replace every occurrence (default false)."),
                        "filePath", "oldString", "newString");
            }

            @Override
            public String execute(JsonNode arguments) throws Exception {
                Path file;
                try {
                    file = workspace.resolve(pathArgument(arguments));
                } catch (SecurityException e) {
                    return error(e.getMessage());
                }
                if (!Files.isRegularFile(file)) {
                    return error("not a file: " + workspace.display(file));
                }
                String oldText = textArgument(arguments, "oldString", "oldText");
                String newText = textArgument(arguments, "newString", "newText");
                if (oldText.isEmpty()) {
                    return error("'oldString' is required");
                }
                if (oldText.equals(newText)) {
                    return error("oldString and newString are identical — nothing to change");
                }
                boolean replaceAll = arguments.path("replaceAll").asBoolean(false);

                String content = Files.readString(file, StandardCharsets.UTF_8);
                int occurrences = countOccurrences(content, oldText);
                if (occurrences == 0) {
                    return error("oldString not found in content");
                }
                if (!replaceAll && occurrences > 1) {
                    return error("Found multiple matches for oldString in " + workspace.display(file)
                            + " (" + occurrences + " occurrences). Provide more surrounding context "
                            + "to make it unique, or set replaceAll=true.");
                }

                String updated = replaceAll
                        ? content.replace(oldText, newText)
                        : replaceFirst(content, oldText, newText);
                Files.writeString(file, updated, StandardCharsets.UTF_8);

                ObjectNode result = mapper.createObjectNode();
                result.put("path", workspace.display(file));
                result.put("replacements", replaceAll ? occurrences : 1);
                result.put("bytes", updated.getBytes(StandardCharsets.UTF_8).length);
                return result.toString();
            }
        };
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The path argument.
     *
     * <p>The OpenCode CLI calls it {@code filePath}; the older {@code path} is still accepted so a
     * caller written against the first version keeps working.</p>
     */
    private static String pathArgument(JsonNode arguments) {
        String value = arguments.path("filePath").asText("");
        return value.isBlank() ? arguments.path("path").asText("") : value;
    }

    /** A text argument, taking the CLI's name first and the older name second. */
    private static String textArgument(JsonNode arguments, String name, String legacyName) {
        String value = arguments.path(name).asText("");
        return value.isBlank() ? arguments.path(legacyName).asText("") : value;
    }

    /** Walks regular files under {@code base}, skipping {@link #SKIP_DIRS} and hidden directories. */
    private void walk(Path base, java.util.function.Consumer<Path> visitor) throws IOException {
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base)) {
                    String name = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    visitor.accept(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String replaceFirst(String haystack, String needle, String replacement) {
        int index = haystack.indexOf(needle);
        return index < 0 ? haystack
                : haystack.substring(0, index) + replacement + haystack.substring(index + needle.length());
    }

    private String error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        return node.toString();
    }

    /** Builds a JSON-Schema property node. */
    private ObjectNode property(String type, String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }

    /** name, type, description triples → a properties object. */
    private ObjectNode props(String... triples) {
        ObjectNode properties = mapper.createObjectNode();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            properties.set(triples[i], property(triples[i + 1], triples[i + 2]));
        }
        return properties;
    }

    /** Turns a properties object plus required names into a full object schema. */
    private ObjectNode schemas(ObjectNode properties, String... required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode requiredArray = schema.putArray("required");
        for (String name : required) {
            requiredArray.add(name);
        }
        return schema;
    }
}
