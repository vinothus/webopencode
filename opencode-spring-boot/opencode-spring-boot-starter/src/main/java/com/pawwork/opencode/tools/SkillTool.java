package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The {@code skill} tool: loads a skill's instructions into the conversation.
 *
 * <p>A skill is a directory holding a {@code SKILL.md} with YAML frontmatter:</p>
 *
 * <pre>{@code
 * ---
 * name: office-docx
 * description: "Native .docx generation ..."
 * ---
 *
 * # office-docx
 * ...instructions...
 * }</pre>
 *
 * <p>The directory is {@code opencode.tools.skills-dir}; nothing is hardcoded, so the application
 * stays portable. When it is unset the tool reports that skills are not configured, and the available
 * skill names are advertised in the tool description so the model knows what it may ask for.</p>
 */
public final class SkillTool {

    /** Frontmatter block at the very start of the file. */
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final int MAX_SKILL_BYTES = 256 * 1024;

    private final ObjectMapper mapper;
    private final Path skillsDir;

    public SkillTool(ObjectMapper mapper, String configuredDir) {
        this.mapper = mapper;
        this.skillsDir = configuredDir == null || configuredDir.isBlank()
                ? null : Path.of(configuredDir).toAbsolutePath().normalize();
    }

    /** Discovered skills, keyed by name, in stable order. Recomputed per call — the dir is tiny. */
    public Map<String, Skill> discover() {
        Map<String, Skill> found = new LinkedHashMap<>();
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return found;
        }
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(dir -> {
                        Path manifest = dir.resolve("SKILL.md");
                        if (!Files.isRegularFile(manifest)) {
                            return;
                        }
                        try {
                            String body = Files.readString(manifest, StandardCharsets.UTF_8);
                            Map<String, String> front = parseFrontmatter(body);
                            String name = front.getOrDefault("name", dir.getFileName().toString());
                            String description = front.getOrDefault("description", "");
                            found.put(name, new Skill(name, description, dir, body));
                        } catch (IOException ignored) {
                            // unreadable skill — skip it
                        }
                    });
        } catch (IOException ignored) {
            // directory vanished mid-scan
        }
        return found;
    }

    public ToolHandler handler() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "skill";
            }

            @Override
            public String description() {
                Map<String, Skill> skills = discover();
                if (skills.isEmpty()) {
                    return "Load a specialized skill's instructions into the conversation. "
                            + "No skills are configured on this server.";
                }
                StringBuilder sb = new StringBuilder(
                        "Load a specialized skill when the task matches one of these. "
                                + "The skill's instructions are returned for you to follow:\n");
                skills.values().forEach(skill -> sb.append("- ").append(skill.name())
                        .append(": ").append(abbreviate(skill.description(), 200)).append('\n'));
                return sb.toString();
            }

            @Override
            public JsonNode parameters() {
                ObjectNode properties = mapper.createObjectNode();
                ObjectNode name = mapper.createObjectNode();
                name.put("type", "string");
                name.put("description", "The skill name, from the available_skills list.");
                properties.set("name", name);

                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", properties);
                schema.putArray("required").add("name");
                return schema;
            }

            @Override
            public String execute(JsonNode arguments) {
                String requested = arguments.path("name").asText("").trim();
                if (requested.isEmpty()) {
                    return error("'name' is required");
                }
                Map<String, Skill> skills = discover();
                if (skills.isEmpty()) {
                    return error("no skills are configured on this server"
                            + (skillsDir == null ? " (opencode.tools.skills-dir is not set)" : ""));
                }
                Skill skill = skills.get(requested);
                if (skill == null) {
                    return error("unknown skill: " + requested + " (available: " + skills.keySet() + ")");
                }
                try {
                    long size = Files.size(skill.directory().resolve("SKILL.md"));
                    if (size > MAX_SKILL_BYTES) {
                        return error("skill file is " + size + " bytes, over the "
                                + MAX_SKILL_BYTES + "-byte limit");
                    }
                    String body = skill.body();
                    ObjectNode result = mapper.createObjectNode();
                    result.put("name", skill.name());
                    result.put("description", skill.description());
                    // The model needs a concrete path for the skill's scripts and assets.
                    result.put("baseDirectory", skill.directory().toString());
                    result.put("instructions", body);
                    return result.toString();
                } catch (IOException e) {
                    return error("could not read skill " + requested + ": " + e.getMessage());
                }
            }
        };
    }

    /** name → description of everything discoverable, for a status endpoint. */
    public List<Map<String, String>> catalogue() {
        List<Map<String, String>> out = new ArrayList<>();
        discover().values().forEach(skill -> out.add(Map.of(
                "name", skill.name(),
                "description", skill.description(),
                "directory", skill.directory().toString())));
        return out;
    }

    public String configuredDir() {
        return skillsDir == null ? "" : skillsDir.toString();
    }

    // ------------------------------------------------------------------ parsing

    /** Minimal YAML frontmatter reader: top-level {@code key: value} pairs, quotes stripped. */
    static Map<String, String> parseFrontmatter(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = FRONTMATTER.matcher(body);
        if (!matcher.find()) {
            return out;
        }
        for (String line : matcher.group(1).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            // A folded block scalar ("key: >") spans lines; keep the simple single-line case.
            if (value.startsWith(">") || value.startsWith("|")) {
                continue;
            }
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private String error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        return node.toString();
    }

    /** One discovered skill. */
    public record Skill(String name, String description, Path directory, String body) {
    }
}
