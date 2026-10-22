package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file tools, and the argument names the model actually sends.
 *
 * <p>The OpenCode CLI's descriptions tell the model to pass {@code filePath}, {@code oldString} and
 * {@code newString}. The gateway hands the model the CLI's schemas, so those are the names that
 * arrive — a tool that only understood {@code path} and {@code oldText} was being called with
 * arguments it could not read.</p>
 */
class FileToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readTakesThePathsTheCliAsksFor(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(new Workspace(root.toString()), mapper);

        JsonNode result = mapper.readTree(tools.read().execute(args("filePath", "a.txt")));

        assertThat(result.path("path").asText()).isEqualTo("a.txt");
        assertThat(result.path("content").asText()).isEqualTo("1: one\n2: two\n");
    }

    @Test
    void theOlderPathNameStillWorks(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(new Workspace(root.toString()), mapper);

        JsonNode result = mapper.readTree(tools.read().execute(args("path", "a.txt")));

        assertThat(result.path("content").asText()).isEqualTo("1: one\n");
    }

    @Test
    void writeAndEditTakeTheCliArgumentNames(@TempDir Path root) throws Exception {
        FileTools tools = new FileTools(new Workspace(root.toString()), mapper);

        JsonNode written = mapper.readTree(tools.write().execute(
                args("filePath", "a.txt", "content", "hello\n")));
        assertThat(written.path("created").asBoolean()).isTrue();
        assertThat(root.resolve("a.txt")).exists();

        JsonNode edited = mapper.readTree(tools.edit().execute(
                args("filePath", "a.txt", "oldString", "hello", "newString", "goodbye")));
        assertThat(edited.path("replacements").asInt()).isEqualTo(1);
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("goodbye\n");
    }

    @Test
    void anEditThatDoesNotMatchSaysSoTheWayTheCliDoes(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello\n", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(new Workspace(root.toString()), mapper);

        JsonNode result = mapper.readTree(tools.edit().execute(
                args("filePath", "a.txt", "oldString", "nope", "newString", "x")));

        assertThat(result.path("error").asText()).contains("oldString not found in content");
    }

    @Test
    void anAmbiguousEditSaysHowManyMatchesThereWere(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "x\nx\n", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(new Workspace(root.toString()), mapper);

        JsonNode result = mapper.readTree(tools.edit().execute(
                args("filePath", "a.txt", "oldString", "x", "newString", "y")));

        assertThat(result.path("error").asText()).contains("Found multiple matches for oldString");
    }

    @Test
    void anAbsolutePathInsideTheWorkspaceIsHonoured(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
        Workspace workspace = new Workspace(root.toString());
        FileTools tools = new FileTools(workspace, mapper);

        JsonNode result = mapper.readTree(tools.read().execute(
                args("filePath", root.resolve("a.txt").toAbsolutePath().toString())));

        assertThat(result.path("content").asText()).isEqualTo("1: one\n");
    }

    @Test
    void aLeadingSlashStillMeansTheWorkspaceRoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
        Workspace workspace = new Workspace(root.toString());
        FileTools tools = new FileTools(workspace, mapper);

        JsonNode result = mapper.readTree(tools.read().execute(args("filePath", "/a.txt")));

        assertThat(result.path("path").asText()).isEqualTo("a.txt");
    }

    private ObjectNode args(String... pairs) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        return node;
    }
}
