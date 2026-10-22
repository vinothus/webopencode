package com.pawwork.opencode.demo.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.prompt.ServerEnvironment;
import com.pawwork.opencode.service.ChatService;
import com.pawwork.opencode.tools.FileTools;
import com.pawwork.opencode.tools.ShellTool;
import com.pawwork.opencode.tools.SkillTool;
import com.pawwork.opencode.tools.TaskTool;
import com.pawwork.opencode.tools.TodoTool;
import com.pawwork.opencode.tools.ToolHandler;
import com.pawwork.opencode.tools.WebFetchTool;
import com.pawwork.opencode.tools.Workspace;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Registers the built-in coding tools the model can actually execute.
 *
 * <p>Ten of the eleven official CLI tool names are implemented here and in the starter:
 * {@code read}, {@code glob}, {@code grep}, {@code write}, {@code edit}, {@code bash},
 * {@code todowrite}, {@code webfetch}, {@code skill} and {@code task}. Only {@code websearch}
 * remains a name advertised to the gateway without a local implementation — it needs a search
 * provider, which this host has no key for.</p>
 */
@Configuration
public class CodingTools {

    @Bean
    public Workspace opencodeWorkspace(OpenCodeProperties properties) {
        return new Workspace(properties.getTools().getWorkspace());
    }

    @Bean
    public FileTools opencodeFileTools(Workspace workspace, ObjectMapper mapper) {
        return new FileTools(workspace, mapper);
    }

    @Bean
    public ShellTool opencodeShellTool(Workspace workspace, ObjectMapper mapper,
                                       ServerEnvironment environment) {
        // The same shell the prompt tells the model about, so the description and the tool agree.
        return new ShellTool(workspace, mapper, environment.launcher());
    }

    @Bean
    public TodoTool opencodeTodoTool(ObjectMapper mapper) {
        return new TodoTool(mapper);
    }

    @Bean
    public WebFetchTool opencodeWebFetchTool(ObjectMapper mapper) {
        return new WebFetchTool(mapper);
    }

    @Bean
    public SkillTool opencodeSkillTool(ObjectMapper mapper, OpenCodeProperties properties) {
        return new SkillTool(mapper, properties.getTools().getSkillsDir());
    }

    @Bean
    public TaskTool opencodeTaskTool(ObjectMapper mapper, OpenCodeProperties properties,
                                     @Lazy ChatService chatService) {
        // The sub-agent runs an isolated turn through the same chat layer: fresh session, its own
        // tool loop, only the final answer returned. @Lazy breaks the ChatService ⇄ TaskTool cycle.
        TaskTool.SubAgent subAgent = (prompt, depth) -> {
            String sessionId = chatService.newSessionId();
            var result = chatService.nested(depth)
                    .chat(sessionId, null, null, prompt);
            return result.getReply();
        };
        return new TaskTool(mapper, subAgent, properties.getTools().getMaxTaskDepth());
    }

    // ------------------------------------------------------------------ the tools themselves

    @Bean
    public ToolHandler readTool(FileTools tools) {
        return tools.read();
    }

    @Bean
    public ToolHandler globTool(FileTools tools) {
        return tools.glob();
    }

    @Bean
    public ToolHandler grepTool(FileTools tools) {
        return tools.grep();
    }

    @Bean
    public ToolHandler writeTool(FileTools tools) {
        return tools.write();
    }

    @Bean
    public ToolHandler editTool(FileTools tools) {
        return tools.edit();
    }

    @Bean
    public ToolHandler bashTool(ShellTool shell) {
        return shell.handler();
    }

    @Bean
    public ToolHandler todowriteTool(TodoTool todos) {
        return todos.handler();
    }

    @Bean
    public ToolHandler webfetchTool(WebFetchTool webFetch) {
        return webFetch.handler();
    }

    @Bean
    public ToolHandler skillTool(SkillTool skills) {
        return skills.handler();
    }

    @Bean
    public ToolHandler taskTool(TaskTool tasks) {
        return tasks.handler();
    }
}
