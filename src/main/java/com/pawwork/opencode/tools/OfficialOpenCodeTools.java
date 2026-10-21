package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.model.ToolDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the 11 official OpenCode CLI tool schemas shipped in {@code opencode_tools.json}.
 *
 * <p>Since 2026-09-18 the Zen free tier rejects any request that does not advertise these tool
 * <em>names</em> with {@code 403 FreeTierError} ("can only be used from within OpenCode"). The
 * schemas themselves are the CLI's own (MIT, opencode 1.18.31).</p>
 */
public final class OfficialOpenCodeTools {

    public static final String RESOURCE = "/opencode_tools.json";

    private OfficialOpenCodeTools() {
    }

    /** Reads the bundled schemas. Returns an empty list if the resource is missing. */
    public static List<ToolDefinition> load(ObjectMapper mapper) {
        try (InputStream in = OfficialOpenCodeTools.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            ToolDefinition[] tools = mapper.readValue(in, ToolDefinition[].class);
            List<ToolDefinition> out = new ArrayList<>(tools.length);
            for (ToolDefinition tool : tools) {
                if (tool != null && !tool.functionName().isBlank()) {
                    out.add(tool);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the bundled OpenCode tool schemas", e);
        }
    }

    /** Names of the official tools, in file order. */
    public static List<String> names(ObjectMapper mapper) {
        List<String> out = new ArrayList<>();
        for (ToolDefinition tool : load(mapper)) {
            out.add(tool.functionName());
        }
        return out;
    }
}
