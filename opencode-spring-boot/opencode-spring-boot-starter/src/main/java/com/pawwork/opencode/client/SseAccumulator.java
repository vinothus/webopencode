package com.pawwork.opencode.client;

import com.pawwork.opencode.model.ChatChunk;
import com.pawwork.opencode.model.ChatMessage;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.model.Choice;
import com.pawwork.opencode.model.ChunkChoice;
import com.pawwork.opencode.model.Delta;
import com.pawwork.opencode.model.ToolCall;
import com.pawwork.opencode.model.ToolFunctionCall;
import com.pawwork.opencode.model.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Folds an SSE chunk stream back into the single {@link ChatResponse} a non-streaming call would
 * have returned.
 *
 * <p>This is a direct port of the accumulation loop in the Android reference client
 * ({@code ChatFragment.postChat}), including its two non-obvious rules:</p>
 * <ul>
 *   <li>tool-call fragments arrive with {@code id}/{@code name} set only in the first chunk — later
 *       fragments carry {@code null} there and must not clobber what was already captured;</li>
 *   <li>{@code delta.content} can be JSON {@code null}, which is different from an absent field —
 *       only a non-null, non-empty fragment is appended.</li>
 * </ul>
 */
public class SseAccumulator {

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, ToolCall> toolCalls = new TreeMap<>();

    private String id;
    private String model;
    private Long created;
    private String finishReason;
    private String role;
    private Usage usage;

    /** Consumes one decoded {@code data:} chunk. Never throws on malformed input. */
    public void accept(ChatChunk chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.getId() != null) {
            id = chunk.getId();
        }
        if (chunk.getModel() != null) {
            model = chunk.getModel();
        }
        if (chunk.getCreated() != null) {
            created = chunk.getCreated();
        }
        if (chunk.getUsage() != null) {
            if (usage == null) {
                usage = new Usage();
            }
            usage.merge(chunk.getUsage());
        }

        ChunkChoice choice = chunk.firstChoice();
        if (choice == null) {
            return;
        }
        if (choice.getFinishReason() != null) {
            finishReason = choice.getFinishReason();
        }
        Delta delta = choice.getDelta();
        if (delta == null) {
            return;
        }
        if (delta.getRole() != null) {
            role = delta.getRole();
        }
        if (delta.getContent() != null && !delta.getContent().isEmpty()) {
            content.append(delta.getContent());
        }
        if (delta.getReasoningContent() != null && !delta.getReasoningContent().isEmpty()) {
            reasoning.append(delta.getReasoningContent());
        }
        if (delta.getToolCalls() != null) {
            for (ToolCall fragment : delta.getToolCalls()) {
                mergeToolFragment(fragment);
            }
        }
    }

    /** Stitches one tool-call fragment into the accumulating call at its {@code index}. */
    private void mergeToolFragment(ToolCall fragment) {
        if (fragment == null) {
            return;
        }
        int index = fragment.getIndex() != null ? fragment.getIndex() : toolCalls.size();
        ToolCall call = toolCalls.computeIfAbsent(index, k -> new ToolCall());
        call.setIndex(index);

        if (fragment.getId() != null && !fragment.getId().isEmpty()) {
            call.setId(fragment.getId());
        }
        if (fragment.getType() != null && !fragment.getType().isEmpty()) {
            call.setType(fragment.getType());
        }

        ToolFunctionCall incoming = fragment.getFunction();
        if (incoming == null) {
            return;
        }
        ToolFunctionCall target = call.getFunction();
        if (target == null) {
            target = new ToolFunctionCall();
            call.setFunction(target);
        }
        if (incoming.getName() != null && !incoming.getName().isEmpty()) {
            target.setName(incoming.getName());
        }
        if (incoming.getArguments() != null) {
            String previous = target.getArguments();
            target.setArguments((previous == null ? "" : previous) + incoming.getArguments());
        }
    }

    /** @return the number of tool calls assembled so far. */
    public int toolCallCount() {
        return toolCalls.size();
    }

    /** @return visible text — the model's content, or its reasoning when it wrote none. */
    public String text() {
        return content.length() > 0 ? content.toString() : reasoning.toString();
    }

    public boolean hasText() {
        return content.length() > 0 || reasoning.length() > 0;
    }

    /** Builds the same response body the non-streaming path would have produced. */
    public ChatResponse toResponse() {
        ChatMessage message = new ChatMessage();
        message.setRole(role == null || role.isBlank() ? "assistant" : role);
        message.setContent(ChatMessage.text(text()));

        if (!toolCalls.isEmpty()) {
            List<ToolCall> assembled = new ArrayList<>(toolCalls.values());
            for (ToolCall call : assembled) {
                if (call.getType() == null || call.getType().isBlank()) {
                    call.setType("function");
                }
                if (call.getFunction() == null) {
                    call.setFunction(new ToolFunctionCall());
                }
                if (call.getFunction().getArguments() == null) {
                    call.getFunction().setArguments("{}");
                }
            }
            message.setToolCalls(assembled);
        }
        if (reasoning.length() > 0) {
            message.setReasoningContent(reasoning.toString());
        }

        Choice choice = new Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(finishReason);

        ChatResponse response = new ChatResponse();
        response.setId(id == null ? "chatcmpl-stream" : id);
        response.setObject("chat.completion");
        response.setCreated(created == null ? System.currentTimeMillis() / 1000 : created);
        response.setModel(model);
        response.setChoices(List.of(choice));
        response.setUsage(usage);
        return response;
    }
}
