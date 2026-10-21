package com.pawwork.opencode.tools;

import com.pawwork.opencode.model.ChatChunk;
import com.pawwork.opencode.model.ChatMessage;
import com.pawwork.opencode.model.ChatRequest;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.model.ChunkChoice;
import com.pawwork.opencode.model.ToolCall;
import com.pawwork.opencode.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps an OpenAI pass-through honest about its contract: a client that declared
 * {@code calculate} must never be handed a {@code tool_calls} entry for {@code bash}.
 *
 * <p>This matters because the OpenCode free tier refuses any gateway body that is missing the 11
 * official CLI tool names, so the starter injects them into every upstream request — including
 * requests from Cursor, LangChain or any other OpenAI SDK that never asked for them.</p>
 */
public final class DeclaredTools {

    private DeclaredTools() {
    }

    /** The tool names the caller actually advertised (empty when it sent no {@code tools} at all). */
    public static Set<String> declared(ChatRequest request) {
        if (request == null || request.getTools() == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (ToolDefinition definition : request.getTools()) {
            if (definition == null) {
                continue;
            }
            String name = definition.functionName();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /** Every tool call the response asks for, in order. */
    public static List<ToolCall> toolCalls(ChatResponse response) {
        if (response == null) {
            return List.of();
        }
        ChatMessage message = response.firstMessage();
        List<ToolCall> calls = message.getToolCalls();
        return calls == null ? List.of() : calls;
    }

    /**
     * Names the model asked for that the caller never declared — {@code "(unnamed)} for
     * malformed calls, which a client could not possibly dispatch either.
     */
    public static Set<String> undeclared(ChatResponse response, Set<String> declared) {
        Set<String> foreign = new LinkedHashSet<>();
        Set<String> known = declared == null ? Set.of() : declared;
        for (ToolCall call : toolCalls(response)) {
            String name = call.functionName();
            if (name == null || name.isBlank()) {
                foreign.add("(unnamed)");
            } else if (!known.contains(name)) {
                foreign.add(name);
            }
        }
        return foreign;
    }

    public static boolean hasUndeclared(ChatResponse response, Set<String> declared) {
        return !undeclared(response, declared).isEmpty();
    }

    /** {@code true} once a chunk starts delivering answer text (as opposed to reasoning/tool calls). */
    public static boolean carriesContent(ChatChunk chunk) {
        if (chunk == null) {
            return false;
        }
        ChunkChoice choice = chunk.firstChoice();
        if (choice == null || choice.getDelta() == null) {
            return false;
        }
        String content = choice.getDelta().getContent();
        return content != null && !content.isEmpty();
    }

    /** {@code true} when a chunk contains (part of) a {@code tool_calls} array. */
    public static boolean carriesToolCalls(ChatChunk chunk) {
        if (chunk == null) {
            return false;
        }
        ChunkChoice choice = chunk.firstChoice();
        if (choice == null || choice.getDelta() == null) {
            return false;
        }
        List<ToolCall> calls = choice.getDelta().getToolCalls();
        return calls != null && !calls.isEmpty();
    }

    /** Convenience for callers holding a collection of names rather than a {@code Set}. */
    public static Set<String> setOf(Collection<String> names) {
        return names == null ? Set.of() : new LinkedHashSet<>(names);
    }

    /** A defensive copy, so a caller can keep mutating its own set while a request is in flight. */
    public static List<String> list(Set<String> names) {
        return names == null ? List.of() : new ArrayList<>(names);
    }
}
