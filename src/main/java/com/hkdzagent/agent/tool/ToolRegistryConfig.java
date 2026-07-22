package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.rag.KnowledgeSearchTool;
import com.hkdzagent.agent.rag.LocalKnowledgeBase;
import com.hkdzagent.agent.rag.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

@Configuration
@EnableConfigurationProperties({
        ToolSecurityProperties.class,
        TavilyProperties.class,
        RagProperties.class
})
public class ToolRegistryConfig {

    private final ToolExecutionSupport executionSupport;
    private final WorkspaceFileTool fileTool;
    private final CommandExecuteTool commandTool;
    private final HttpRequestTool httpTool;
    private final WebSearchTool searchTool;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final AgentToolRegistry agentToolRegistry;

    @Autowired
    public ToolRegistryConfig(
            TavilyProperties tavilyProperties,
            RagProperties ragProperties,
            ToolSecurityProperties securityProperties
    ) {
        this(tavilyProperties.apiKey(), ragProperties.indexFile(), securityProperties);
    }

    public ToolRegistryConfig(
            String tavilyApiKey,
            ToolSecurityProperties securityProperties
    ) {
        this(tavilyApiKey, Path.of("data/rag-index.json"), securityProperties);
    }

    public ToolRegistryConfig(
            String tavilyApiKey,
            Path ragIndexFile,
            ToolSecurityProperties securityProperties
    ) {
        ToolPermissionService permissionService = new ToolPermissionService(securityProperties);
        this.executionSupport = new ToolExecutionSupport();
        this.fileTool = new WorkspaceFileTool(permissionService);
        this.commandTool = new CommandExecuteTool(permissionService);
        this.httpTool = new HttpRequestTool(permissionService);
        this.searchTool = new WebSearchTool(tavilyApiKey);
        this.agentToolRegistry = new AgentToolRegistry(List.of(
                fileTool,
                commandTool,
                httpTool,
                searchTool
        ));
        this.knowledgeSearchTool = new KnowledgeSearchTool(new LocalKnowledgeBase(ragIndexFile));
    }

    @Bean
    public AgentToolRegistry agentToolRegistry() {
        return agentToolRegistry;
    }

    @Bean
    public ToolInvocationValidator toolInvocationValidator(
            AgentToolRegistry registry,
            ObjectMapper objectMapper
    ) {
        return new ToolInvocationValidator(registry, objectMapper, 160);
    }

    @Bean
    public ToolAccessPolicy toolAccessPolicy() {
        return ToolAccessPolicy.allowAuthenticated();
    }

    @Bean
    public ToolApprovalCondition toolApprovalCondition(
            ToolConfirmationProperties confirmationProperties
    ) {
        return invocation -> confirmationProperties.requiresApproval(
                invocation.metadata().name());
    }

    @Bean
    public ToolPolicyEngine toolPolicyEngine(
            ToolAccessPolicy accessPolicy,
            ToolApprovalCondition approvalCondition
    ) {
        return new ToolPolicyEngine(accessPolicy, approvalCondition);
    }

    @Bean
    public ToolExecutionPipeline toolExecutionPipeline(
            ToolInvocationValidator validator,
            ToolPolicyEngine policyEngine,
            ToolExecutionJournalRepository journalRepository
    ) {
        return new ToolExecutionPipeline(
                validator, policyEngine, journalRepository, java.time.Clock.systemUTC());
    }

    public record FileRequest(
            @JsonProperty(required = true, value = "filePath")
            @JsonPropertyDescription("Local file path or file name to write.")
            String filePath,

            @JsonProperty(required = true, value = "content")
            @JsonPropertyDescription("Text content to write to the file.")
            String content
    ) {
    }

    @Bean
    @Description("Write text content to a local file inside the configured workspace.")
    public Function<FileRequest, String> fileOperationTool() {
        return request -> executionSupport.execute("fileOperationTool",
                () -> fileTool.execute(new com.hkdzagent.agent.tool.FileRequest(request.filePath(), request.content())),
                "file write failed");
    }

    public record CommandRequest(
            @JsonProperty(required = true, value = "command")
            @JsonPropertyDescription("Allowed local command to execute.")
            String command
    ) {
    }

    @Bean
    @Description("Execute a local command only when it is explicitly allowed.")
    public Function<CommandRequest, String> commandExecuteTool() {
        return request -> executionSupport.execute("commandExecuteTool",
                () -> commandTool.execute(new com.hkdzagent.agent.tool.CommandRequest(request.command())),
                "command execution failed");
    }

    public record WebRequest(
            @JsonProperty(required = true, value = "url")
            @JsonPropertyDescription("HTTP or HTTPS URL to request.")
            String url
    ) {
    }

    @Bean
    @Description("Send an HTTP GET request only to configured domains.")
    public Function<WebRequest, String> httpRequestTool() {
        return request -> executionSupport.execute("httpRequestTool",
                () -> httpTool.execute(new com.hkdzagent.agent.tool.WebRequest(request.url())),
                "network request failed");
    }

    public record SearchRequest(
            @JsonProperty(required = true, value = "query")
            @JsonPropertyDescription("Search query for public web information.")
            String query
    ) {
    }

    @Bean
    @Description("Search public web information through Tavily.")
    public Function<SearchRequest, String> webSearchTool() {
        return request -> executionSupport.execute("webSearchTool",
                () -> searchTool.execute(new com.hkdzagent.agent.tool.SearchRequest(request.query())),
                "search failed");
    }

    public record KnowledgeRequest(
            @JsonProperty(required = true, value = "query")
            @JsonPropertyDescription("Question or search query for the local RAG knowledge base.")
            String query
    ) {
    }

    @Bean
    @Description("Search the local RAG knowledge base and return matching chunks with source references.")
    public Function<KnowledgeRequest, String> knowledgeSearchTool() {
        return request -> executionSupport.execute("knowledgeSearchTool",
                () -> ToolResult.success(knowledgeSearchTool.execute(request.query())),
                "knowledge search failed");
    }
}
